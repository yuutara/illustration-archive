package com.yuutara.illustrationarchive.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Runs backups, restores and imports from the old app against the running app. It belongs to the launcher rather
 * than to Spring: each needs a closed library, so it stops the server and starts it again, and has to outlive each
 * context.
 *
 * <p>One operation at a time per library. A backup starts once the user picked where it goes: the requests in
 * progress finish ({@link MaintenanceGate}), the server stops, the ZIP is written, the server starts again. A restore
 * is first unpacked and checked while the library stays in use; the user then sees what the backup holds and confirms,
 * and only then does the server stop for the switch. If the restored library does not open, the previous one is put
 * back. An import from the old server app works the same way: the old library is copied and checked beside the
 * current one ({@link LegacyMigration}), and confirming swaps it in like a restore. While the server is down,
 * {@link Screen} is all the user sees, so it is told every stage.
 */
final class LibraryMaintenance {

	static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);
	/** How many media problems an import preview lists; the count says how many there are in all. */
	static final int SHOWN_PROBLEMS = 20;
	private static final Logger log = LoggerFactory.getLogger(LibraryMaintenance.class);

	interface Server {

		boolean running();

		MaintenanceGate gate();

		/** @return true once the connection pool, and with it the database, is closed */
		boolean stop();

		/**
		 * Starts the server on the library as it is now; throws when it cannot.
		 *
		 * @param held start with the gate closed, so that no request reaches a library that is not confirmed yet
		 */
		void start(boolean held);

		long count(String table);

		/** An X login is waiting for its callback or trading the code; stopping the server would throw it away. */
		boolean loginInProgress();
	}

	interface Dialogs {

		Optional<Path> saveBackup(String suggestedName);

		Optional<Path> openBackup();

		/** The old server app's {@code application-local.properties}. */
		Optional<Path> openLegacyConfig();

		/** The old app's media folder, asked for when its config names it by a relative path. */
		Optional<Path> chooseLegacyMedia(String configured);
	}

	/** Reads the old app's config file; the app always uses {@link LegacyMigration.Source#read}. */
	interface LegacySources {

		LegacyMigration.Source read(Path config) throws MaintenanceException;
	}

	/** Shown while the server is down, and at the end. */
	interface Screen {

		void stage(Kind kind, String text, double fraction, boolean cancellable);

		void finished(Outcome outcome);
	}

	enum Kind { BACKUP, RESTORE, IMPORT }

	/**
	 * @param location the new backup, or the folder that keeps the replaced library
	 * @param stuck the library is not known to be whole, so nothing may open it in this run, a restore included
	 */
	record Outcome(Kind kind, boolean succeeded, String message, Path location, boolean libraryOpen, boolean stuck) {

		Outcome(Kind kind, boolean succeeded, String message, Path location, boolean libraryOpen) {
			this(kind, succeeded, message, location, libraryOpen, false);
		}
	}

	enum Phase { IDLE, CHECKING, READY, FAILED }

	record Preview(String fileName, String createdAt, String appVersion, String schemaBefore, String schemaAfter,
			LibraryBackup.Summary summary) {
	}

	/**
	 * An old library checked for importing, beside the current one it would replace.
	 *
	 * @param source the MySQL host and database, never the user name, password or URL parameters
	 * @param current what the library holds now, or null when it could not be counted
	 * @param problems the first of the media files that are missing or changed; {@code problemCount} is all of them
	 */
	record ImportPreview(String source, String mediaRoot, String schemaBefore, String schemaAfter,
			LibraryBackup.Summary incoming, LibraryBackup.Summary current, List<String> problems, int problemCount) {
	}

	/**
	 * A restore or an import as the Settings page shows it: being checked, waiting for confirmation, or refused. Only
	 * one at a time; {@code kind} says which, and the preview of that kind is set.
	 */
	record RestoreView(Phase phase, Kind kind, String id, String stage, double fraction, Preview preview,
			ImportPreview importPreview, String message) {

		static final RestoreView IDLE = new RestoreView(Phase.IDLE, null, null, null, 0, null, null, null);
	}

	/** A checked library waiting in {@code recovery/<id>/incoming}, and what it must hold once it is open. */
	private record Pending(Kind kind, String id, long works, long assets) {
	}

	/** @param pending null when the check refused the library and nothing waits to be applied */
	private record Checked(Pending pending, RestoreView view) {
	}

	private interface Check {

		Checked run(LibraryBackup.Progress progress) throws MaintenanceException;
	}

	private final Path home;
	private final Server server;
	private final Dialogs dialogs;
	private final Screen screen;
	private final String appVersion;
	private final LibraryRestore.Mover mover;
	private final LegacySources sources;
	private boolean claimed;
	private boolean maintaining;
	private RestoreView restore = RestoreView.IDLE;
	private Pending pending;
	private volatile boolean cancel;
	private volatile Kind kind = Kind.BACKUP;

	LibraryMaintenance(Path home, Server server, Dialogs dialogs, Screen screen, String appVersion) {
		this(home, server, dialogs, screen, appVersion, LibraryRestore::rename, LegacyMigration.Source::read);
	}

	/**
	 * @param mover the switch's renames; tests make one fail, the app always uses {@link LibraryRestore#rename}
	 * @param sources reads the old app's config; tests point it at an H2 database, the app always reads MySQL
	 */
	LibraryMaintenance(Path home, Server server, Dialogs dialogs, Screen screen, String appVersion,
			LibraryRestore.Mover mover, LegacySources sources) {
		this.home = home;
		this.server = server;
		this.dialogs = dialogs;
		this.screen = screen;
		this.appVersion = appVersion;
		this.mover = mover;
		this.sources = sources;
	}

	/** True while the server is or may be down for a backup, restore or import; quitting then has to wait. */
	synchronized boolean maintaining() {
		return maintaining;
	}

	synchronized RestoreView restoreView() {
		return restore;
	}

	/** Cancels what can still be cancelled: waiting for requests, writing a backup, or checking a restore or import. */
	void cancel() {
		cancel = true;
	}

	/** The app is quitting: a check stops, and a checked backup or old library nobody confirmed is dropped. */
	void close() {
		cancel = true;
		RestoreView view = restoreView();
		if (view.phase() == Phase.READY) {
			cancelRestore(view.id());
		}
	}

	/**
	 * Asks where to save the backup and starts it.
	 *
	 * @return false when the user closed the dialog
	 */
	boolean backup() throws MaintenanceException {
		claim();
		try {
			Optional<Path> chosen = dialogs.saveBackup(LibraryBackup.suggestedName());
			if (chosen.isEmpty()) {
				release();
				return false;
			}
			Path target = LibraryBackup.checkDestination(home, chosen.get());
			begin(Kind.BACKUP, () -> runBackup(target));
			return true;
		} catch (MaintenanceException | RuntimeException refused) {
			release();
			throw refused;
		}
	}

	/** Asks for a backup file and starts checking it; {@link #restoreView} follows the check. */
	RestoreView check() throws MaintenanceException {
		claim();
		Optional<Path> chosen;
		try {
			chosen = dialogs.openBackup();
		} catch (RuntimeException failed) {
			release();
			throw failed;
		}
		if (chosen.isEmpty()) {
			release();
		} else {
			startCheck(chosen.get());
		}
		return restoreView();
	}

	/** Starts checking a backup the caller chose itself, as the window shown after a failed start does. */
	void check(Path backup) throws MaintenanceException {
		claim();
		startCheck(backup);
	}

	private void startCheck(Path backup) {
		startCheck(Kind.RESTORE, "正在打开备份…", progress -> {
			LibraryRestore.Candidate prepared = LibraryRestore.prepare(home, backup, progress);
			LibraryBackup.Manifest manifest = prepared.manifest();
			LibraryBackup.Summary summary = manifest.summary();
			return new Checked(new Pending(Kind.RESTORE, prepared.id(), summary.works(), summary.assets()),
					new RestoreView(Phase.READY, Kind.RESTORE, prepared.id(), null, 1, new Preview(prepared.fileName(),
							manifest.createdAt(), manifest.appVersion(), prepared.schemaBefore(), prepared.schemaAfter(),
							summary), null, null));
		}, Function.identity(), UnaryOperator.identity());
	}

	/**
	 * Asks for the old server app's config file and, when it names the media folder by a relative path, for that
	 * folder; then copies and checks the old library in the background. {@link #restoreView} follows the check.
	 */
	RestoreView checkImport() throws MaintenanceException {
		claim();
		try {
			Optional<Path> config = dialogs.openLegacyConfig();
			if (config.isEmpty()) {
				release();
				return restoreView();
			}
			LegacyMigration.Source source = sources.read(config.get());
			Optional<Path> media = source.mediaRoot();
			if (media.isEmpty()) {
				media = dialogs.chooseLegacyMedia(source.configuredMediaRoot());
				if (media.isEmpty()) {
					release();
					return restoreView();
				}
			}
			if (!Files.isDirectory(media.get())) {
				throw new MaintenanceException("找不到旧版的媒体文件夹：" + media.get() + "。请确认它还在，或在旧版配置里改成实际的位置。");
			}
			startImport(source, media.get());
			return restoreView();
		} catch (MaintenanceException | RuntimeException refused) {
			release();
			throw refused;
		}
	}

	private void startImport(LegacyMigration.Source source, Path media) {
		startCheck(Kind.IMPORT, "正在连接旧版图库的数据库…", progress -> {
			LibraryRestore.Prepared<LegacyMigration.Result> prepared = LibraryRestore.prepare(home, incoming -> {
				LegacyMigration.Result copied = LegacyMigration.prepare(source, media, incoming, progress);
				if (progress.cancelled()) {
					throw MaintenanceException.cancelled();
				}
				return copied;
			});
			LegacyMigration.Result result = prepared.value();
			Map<String, Long> rows = result.rows();
			List<String> problems = result.problems();
			ImportPreview preview = new ImportPreview(source.describe(), media.toString(), result.schemaBefore(),
					result.schemaAfter(), new LibraryBackup.Summary(rows.getOrDefault("illustration", 0L),
							rows.getOrDefault("asset", 0L), rows.getOrDefault("author", 0L), rows.getOrDefault("tag", 0L),
							rows.getOrDefault("x_like_item", 0L), result.files(), result.bytes()),
					currentLibrary(), List.copyOf(problems.subList(0, Math.min(SHOWN_PROBLEMS, problems.size()))),
					problems.size());
			if (!problems.isEmpty()) {
				// Settings never imports a library with missing or changed files, so the copy is of no further use.
				LibraryRestore.discard(home, prepared.id());
				return new Checked(null, new RestoreView(Phase.FAILED, Kind.IMPORT, null, null, 0, null, preview,
						"旧版图库里有 " + problems.size() + " 个媒体文件缺失或内容与记录不符，不能导入。当前图库没有任何改动。"));
			}
			return new Checked(new Pending(Kind.IMPORT, prepared.id(), preview.incoming().works(), preview.incoming().assets()),
					new RestoreView(Phase.READY, Kind.IMPORT, prepared.id(), null, 1, null, preview, null));
		}, source::scrub, source::redact);
	}

	/** @return what the library holds now, or null when the server cannot say */
	private LibraryBackup.Summary currentLibrary() {
		if (!server.running()) {
			return null;
		}
		try {
			return new LibraryBackup.Summary(server.count("illustration"), server.count("asset"), server.count("author"),
					server.count("tag"), server.count("x_like_item"), 0, 0);
		} catch (RuntimeException unavailable) {
			log.warn("The current library could not be counted for the import preview.", unavailable);
			return null;
		}
	}

	/**
	 * Runs a check in the background while the library stays in use; {@link #restoreView} follows its stages.
	 *
	 * @param forLog what the log may see of a failure; for an import, without the password or URL parameters
	 * @param forUser the same for the message the page shows
	 */
	private void startCheck(Kind checking, String firstStage, Check check, Function<Throwable, Throwable> forLog,
			UnaryOperator<String> forUser) {
		cancel = false;
		synchronized (this) {
			restore = new RestoreView(Phase.CHECKING, checking, null, firstStage, -1, null, null, null);
		}
		thread("desktop-" + checking.name().toLowerCase(Locale.ROOT) + "-check", () -> {
			try {
				Checked checked = check.run(new LibraryBackup.Progress() {
					@Override
					public void update(String stage, double fraction) {
						synchronized (LibraryMaintenance.this) {
							restore = new RestoreView(Phase.CHECKING, checking, null, stage, fraction, null, null, null);
						}
					}

					@Override
					public boolean cancelled() {
						return cancel;
					}
				});
				synchronized (this) {
					pending = checked.pending();
					restore = checked.view();
					// A checked library keeps the slot until the user applies or cancels it; a refusal frees it.
					claimed = pending != null;
				}
			} catch (MaintenanceException | RuntimeException refused) {
				boolean cancelled = refused instanceof MaintenanceException maintenance && maintenance.isCancelled();
				if (!cancelled) {
					log.warn("A library could not be prepared to replace the current one.", forLog.apply(refused));
				}
				synchronized (this) {
					restore = cancelled ? RestoreView.IDLE
							: new RestoreView(Phase.FAILED, checking, null, null, 0, null, null, forUser.apply(message(refused)));
					claimed = false;
				}
			}
		});
	}

	/** Cancels the check in progress, drops a checked backup or old library, or clears a refusal. */
	void cancelRestore(String id) {
		Pending dropped = null;
		synchronized (this) {
			switch (restore.phase()) {
				case CHECKING -> cancel = true;
				case READY -> {
					if (maintaining || !restore.id().equals(id)) {
						return;
					}
					dropped = pending;
					pending = null;
					restore = RestoreView.IDLE;
					claimed = false;
				}
				case FAILED -> restore = RestoreView.IDLE;
				default -> { }
			}
		}
		if (dropped != null) {
			LibraryRestore.discard(home, dropped.id());
		}
	}

	/** Replaces the library with the checked backup the user confirmed. */
	void restore(String id) throws MaintenanceException {
		apply(Kind.RESTORE, id);
	}

	/** Replaces the library with the checked backup or old library the user confirmed. */
	void apply(Kind applying, String id) throws MaintenanceException {
		Pending confirmed;
		synchronized (this) {
			if (restore.phase() != Phase.READY || restore.kind() != applying || maintaining || !restore.id().equals(id)) {
				throw new MaintenanceException(applying == Kind.IMPORT ? "这份旧版图库已经不在等待导入，请重新选择。"
						: "这份备份已经不在等待恢复，请重新选择。");
			}
			confirmed = pending;
			// Under the same lock as the check, so a second click cannot start a second switch.
			maintaining = true;
		}
		begin(applying, () -> runSwitch(confirmed));
	}

	private synchronized void claim() throws MaintenanceException {
		if (claimed) {
			throw new MaintenanceException("另一项备份、恢复或导入正在进行，请等它结束。");
		}
		claimed = true;
	}

	private synchronized void release() {
		claimed = false;
	}

	private void begin(Kind kind, Supplier<Outcome> work) {
		this.kind = kind;
		cancel = false;
		synchronized (this) {
			claimed = true;
			maintaining = true;
		}
		thread("desktop-maintenance", () -> {
			Outcome outcome;
			try {
				outcome = work.get();
			} catch (RuntimeException unexpected) {
				log.error("Library maintenance failed unexpectedly.", unexpected);
				outcome = new Outcome(kind, false, "出现意外错误：" + message(unexpected), null, server.running());
			}
			synchronized (this) {
				maintaining = false;
				// A switch that never started keeps its checked library for another try.
				claimed = restore.phase() == Phase.READY;
			}
			screen.finished(outcome);
		});
	}

	private Outcome runBackup(Path target) {
		String refused = quiesce();
		if (refused != null) {
			return new Outcome(Kind.BACKUP, false, refused, null, server.running());
		}
		LibraryBackup.Manifest written = null;
		String problem = null;
		try {
			written = LibraryBackup.create(home, target, appVersion, progress(true));
		} catch (MaintenanceException failed) {
			if (!failed.isCancelled()) {
				log.warn("The backup failed.", failed);
			}
			problem = failed.isCancelled() ? "已取消，没有生成备份。" : failed.getMessage();
		}
		String reopenProblem = reopen();
		String reopened = reopenProblem == null ? "" : "\n\n但图库没能重新打开：" + reopenProblem;
		if (written == null) {
			return new Outcome(Kind.BACKUP, false, problem + reopened, null, server.running());
		}
		LibraryBackup.Summary summary = written.summary();
		return new Outcome(Kind.BACKUP, true, "备份已保存：" + target + "\n" + summary.works() + " 件作品，" + summary.files()
				+ " 个文件，共 " + LibraryBackup.size(summary.bytes()) + "。" + reopened, target, server.running());
	}

	/** Swaps the checked library in and opens it; if it does not check out, the previous one is put back. */
	private Outcome runSwitch(Pending confirmed) {
		Kind switching = confirmed.kind();
		String verb = switching == Kind.IMPORT ? "导入" : "恢复";
		String refused = quiesce();
		if (refused != null) {
			return new Outcome(switching, false, refused, null, server.running());
		}
		stage("正在替换图库…", -1, false);
		Path previous;
		try {
			previous = LibraryRestore.switchTo(home, confirmed.id(), mover);
		} catch (MaintenanceException failed) {
			log.warn("Switching libraries failed.", failed);
			forget(confirmed);
			if (failed.isStuck()) {
				// The undo did not finish either: opening now would start an empty library where the old one belongs.
				return new Outcome(switching, false, failed.getMessage(), null, false, true);
			}
			String reopenProblem = reopen();
			return new Outcome(switching, false, failed.getMessage()
					+ (reopenProblem == null ? "" : "\n\n但图库没能重新打开：" + reopenProblem), null, server.running());
		}
		stage("正在打开" + verb + "后的图库…", -1, false);
		String problem = openSwitched(confirmed);
		forget(confirmed);
		if (problem == null) {
			return new Outcome(switching, true, succeeded(switching, previous), previous, true);
		}
		log.warn("The library switched in did not open: {}", problem);
		stage(verb + "后的图库没能打开，正在退回" + verb + "前的图库…", -1, false);
		if (server.running()) {
			server.stop();
		}
		try {
			LibraryRestore.rollBack(home, confirmed.id());
		} catch (MaintenanceException stuck) {
			log.error("Rolling back the switch failed.", stuck);
			return new Outcome(switching, false, verb + "后的图库没能打开（" + problem + "）。\n" + stuck.getMessage(), null,
					false, true);
		}
		String reopenProblem = reopen();
		return new Outcome(switching, false, verb + "后的图库没能打开（" + problem + "），已退回" + verb + "前的图库"
				+ (reopenProblem == null ? "，可以继续使用。" : "。\n但" + verb + "前的图库也没能打开：" + reopenProblem), null,
				server.running());
	}

	private static String succeeded(Kind switched, Path previous) {
		if (switched == Kind.RESTORE) {
			return "已从备份恢复整个图库。\n恢复前的图库原样保留在：" + previous + "\n确认不再需要后，可以自己删除这个文件夹。";
		}
		return "已从旧版导入整个图库。\n导入前的图库原样保留在：" + previous + "\n确认不再需要后，可以自己删除这个文件夹。"
				+ "\n\n旧版的 X 登录不会导入，本应用的 X 登录和设置保持不变；还没有登录 X 时，请在设置里授权。"
				+ "导入的同步进度会在第一次同步时核对属于哪个 X 账号。";
	}

	/**
	 * Starts on the switched-in library with the gate closed, and opens it only once the library checked out and is
	 * recorded as kept. Until then a request from any tab would write to a library that may still be rolled back.
	 *
	 * @return null once the library runs, holds what was checked, and is recorded as kept
	 */
	private String openSwitched(Pending confirmed) {
		try {
			server.start(true);
			if (server.count("illustration") != confirmed.works() || server.count("asset") != confirmed.assets()) {
				return "作品数量与" + (confirmed.kind() == Kind.IMPORT ? "检查时的旧版图库" : "备份") + "不符";
			}
			LibraryRestore.confirm(home, confirmed.id());
			server.gate().open();
			return null;
		} catch (MaintenanceException | RuntimeException failed) {
			return message(failed);
		}
	}

	private synchronized void forget(Pending used) {
		if (pending == used) {
			pending = null;
			restore = RestoreView.IDLE;
		}
	}

	/**
	 * Closes the gate, waits for the requests inside, and stops the server.
	 *
	 * @return why not, or null once the database is closed
	 */
	private String quiesce() {
		if (!server.running()) {
			return null;
		}
		stage("正在等待进行中的操作结束（例如同步或导入）…", -1, true);
		MaintenanceGate gate = server.gate();
		boolean idle;
		try {
			idle = gate.close(DRAIN_TIMEOUT, () -> cancel);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			idle = false;
		}
		if (!idle || cancel) {
			gate.open();
			return cancel ? "已取消，图库没有任何改动。"
					: "还有操作在进行（例如同步或导入），等了 " + DRAIN_TIMEOUT.toSeconds() + " 秒仍未结束。图库没有任何改动，请稍后再试。";
		}
		// Checked again here, not only before the file dialog: a login started meanwhile in another tab. With the
		// gate closed no new one can start, so this answer holds until the server stops.
		if (server.loginInProgress()) {
			gate.open();
			return "X 授权正在进行，请先完成或取消后再试。图库没有任何改动。";
		}
		stage("正在关闭图库…", -1, false);
		if (!server.stop()) {
			String reopenProblem = reopen();
			return "没能确认数据库已经关闭，所以没有继续，图库没有任何改动。"
					+ (reopenProblem == null ? "" : "\n\n但图库没能重新打开：" + reopenProblem);
		}
		return null;
	}

	/** @return null once the server runs again, otherwise what went wrong */
	private String reopen() {
		stage("正在重新打开图库…", -1, false);
		try {
			server.start(false);
			return null;
		} catch (RuntimeException failed) {
			log.error("The library did not open again.", failed);
			return message(failed);
		}
	}

	private void stage(String text, double fraction, boolean cancellable) {
		screen.stage(kind, text, fraction, cancellable);
	}

	private LibraryBackup.Progress progress(boolean cancellable) {
		return new LibraryBackup.Progress() {
			@Override
			public void update(String stage, double fraction) {
				stage(stage, fraction, cancellable);
			}

			@Override
			public boolean cancelled() {
				return cancel;
			}
		};
	}

	static String message(Throwable failure) {
		if (failure instanceof MaintenanceException) {
			return failure.getMessage();
		}
		// The deepest cause that says something: an EOFException at the bottom of a damaged H2 file has no message.
		String message = null;
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null) {
				message = cause.getMessage();
			}
		}
		return message == null ? failure.getClass().getName() : message;
	}

	/** What the launcher's window says about a checked backup before the user confirms it. */
	static String describe(Preview preview) {
		LibraryBackup.Summary summary = preview.summary();
		String upgrade = preview.schemaBefore().equals(preview.schemaAfter()) ? ""
				: "\n数据库：备份时为 V" + preview.schemaBefore() + "，恢复时升级到 V" + preview.schemaAfter();
		return "备份文件：" + preview.fileName()
				+ "\n备份时间：" + time(preview.createdAt()) + "（应用版本 " + preview.appVersion() + "）"
				+ "\n内容：" + summary.works() + " 件作品、" + summary.assets() + " 张图片或视频、" + summary.authors() + " 位作者、"
				+ summary.tags() + " 个标签、" + summary.inbox() + " 条 X 收藏，共 " + LibraryBackup.size(summary.bytes()) + upgrade
				+ "\n\n恢复会用这个备份替换整个图库，备份之后的改动会从图库中消失。当前图库会先原样保留在数据文件夹的 recovery 里。"
				+ "X 登录、API Key 和应用设置保持不变。";
	}

	static String time(String isoTime) {
		try {
			return OffsetDateTime.parse(isoTime).atZoneSameInstant(ZoneId.systemDefault())
					.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		} catch (RuntimeException unreadable) {
			return String.valueOf(isoTime);
		}
	}

	private static void thread(String name, Runnable work) {
		Thread thread = new Thread(work, name);
		// It may start Spring again, which needs the application's class loader rather than the request thread's.
		thread.setContextClassLoader(LibraryMaintenance.class.getClassLoader());
		thread.setDaemon(true);
		thread.start();
	}
}
