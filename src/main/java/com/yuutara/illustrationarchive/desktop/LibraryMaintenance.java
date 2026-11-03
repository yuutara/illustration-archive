package com.yuutara.illustrationarchive.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs backups and restores against the running app. It belongs to the launcher rather than to Spring: a backup or a
 * restore needs a closed library, so it stops the server and starts it again, and has to outlive each context.
 *
 * <p>One operation at a time per library. A backup starts once the user picked where it goes: the requests in
 * progress finish ({@link MaintenanceGate}), the server stops, the ZIP is written, the server starts again. A restore
 * is first unpacked and checked while the library stays in use; the user then sees what the backup holds and confirms,
 * and only then does the server stop for the switch. If the restored library does not open, the previous one is put
 * back. While the server is down, {@link Screen} is all the user sees, so it is told every stage.
 */
final class LibraryMaintenance {

	static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);
	private static final Logger log = LoggerFactory.getLogger(LibraryMaintenance.class);

	interface Server {

		boolean running();

		MaintenanceGate gate();

		/** @return true once the connection pool, and with it the database, is closed */
		boolean stop();

		/** Starts the server on the library as it is now; throws when it cannot. */
		void start();

		long count(String table);
	}

	interface Dialogs {

		Optional<Path> saveBackup(String suggestedName);

		Optional<Path> openBackup();
	}

	/** Shown while the server is down, and at the end. */
	interface Screen {

		void stage(Kind kind, String text, double fraction, boolean cancellable);

		void finished(Outcome outcome);
	}

	enum Kind { BACKUP, RESTORE }

	/** @param location the new backup, or the folder that keeps the replaced library */
	record Outcome(Kind kind, boolean succeeded, String message, Path location, boolean libraryOpen) {
	}

	enum Phase { IDLE, CHECKING, READY, FAILED }

	record Preview(String fileName, String createdAt, String appVersion, String schemaBefore, String schemaAfter,
			LibraryBackup.Summary summary) {
	}

	/** The restore as the Settings page shows it: being checked, waiting for confirmation, or refused. */
	record RestoreView(Phase phase, String id, String stage, double fraction, Preview preview, String message) {

		static final RestoreView IDLE = new RestoreView(Phase.IDLE, null, null, 0, null, null);
	}

	private final Path home;
	private final Server server;
	private final Dialogs dialogs;
	private final Screen screen;
	private final String appVersion;
	private boolean claimed;
	private boolean maintaining;
	private RestoreView restore = RestoreView.IDLE;
	private LibraryRestore.Candidate candidate;
	private volatile boolean cancel;
	private volatile Kind kind = Kind.BACKUP;

	LibraryMaintenance(Path home, Server server, Dialogs dialogs, Screen screen, String appVersion) {
		this.home = home;
		this.server = server;
		this.dialogs = dialogs;
		this.screen = screen;
		this.appVersion = appVersion;
	}

	/** True while the server is or may be down for a backup or restore; quitting then has to wait. */
	synchronized boolean maintaining() {
		return maintaining;
	}

	synchronized RestoreView restoreView() {
		return restore;
	}

	/** Cancels what can still be cancelled: waiting for requests, writing a backup, or checking a restore. */
	void cancel() {
		cancel = true;
	}

	/** The app is quitting: a check stops, and a checked backup nobody confirmed is dropped. */
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
		cancel = false;
		synchronized (this) {
			restore = new RestoreView(Phase.CHECKING, null, "正在打开备份…", -1, null, null);
		}
		thread("desktop-restore-check", () -> {
			try {
				LibraryRestore.Candidate prepared = LibraryRestore.prepare(home, backup, new LibraryBackup.Progress() {
					@Override
					public void update(String stage, double fraction) {
						synchronized (LibraryMaintenance.this) {
							restore = new RestoreView(Phase.CHECKING, null, stage, fraction, null, null);
						}
					}

					@Override
					public boolean cancelled() {
						return cancel;
					}
				});
				synchronized (this) {
					candidate = prepared;
					LibraryBackup.Manifest manifest = prepared.manifest();
					restore = new RestoreView(Phase.READY, prepared.id(), null, 1, new Preview(prepared.fileName(),
							manifest.createdAt(), manifest.appVersion(), prepared.schemaBefore(), prepared.schemaAfter(),
							manifest.summary()), null);
					// The slot stays taken until the user restores or cancels.
				}
			} catch (MaintenanceException | RuntimeException refused) {
				if (!(refused instanceof MaintenanceException maintenance && maintenance.isCancelled())) {
					log.warn("A backup could not be prepared for restoring.", refused);
				}
				synchronized (this) {
					restore = refused instanceof MaintenanceException maintenance && maintenance.isCancelled() ? RestoreView.IDLE
							: new RestoreView(Phase.FAILED, null, null, 0, null, message(refused));
					claimed = false;
				}
			}
		});
	}

	/** Cancels the check in progress, drops a checked backup, or clears a refusal. */
	void cancelRestore(String id) {
		LibraryRestore.Candidate dropped = null;
		synchronized (this) {
			switch (restore.phase()) {
				case CHECKING -> cancel = true;
				case READY -> {
					if (maintaining || !restore.id().equals(id)) {
						return;
					}
					dropped = candidate;
					candidate = null;
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
		LibraryRestore.Candidate confirmed;
		synchronized (this) {
			if (restore.phase() != Phase.READY || maintaining || !restore.id().equals(id)) {
				throw new MaintenanceException("这份备份已经不在等待恢复，请重新选择。");
			}
			confirmed = candidate;
			// Under the same lock as the check, so a second click cannot start a second switch.
			maintaining = true;
		}
		begin(Kind.RESTORE, () -> runRestore(confirmed));
	}

	private synchronized void claim() throws MaintenanceException {
		if (claimed) {
			throw new MaintenanceException("另一项备份或恢复正在进行，请等它结束。");
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
				// A restore that never reached the switch keeps its checked backup for another try.
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

	private Outcome runRestore(LibraryRestore.Candidate confirmed) {
		String refused = quiesce();
		if (refused != null) {
			return new Outcome(Kind.RESTORE, false, refused, null, server.running());
		}
		stage("正在替换图库…", -1, false);
		Path previous;
		try {
			previous = LibraryRestore.switchTo(home, confirmed.id());
		} catch (MaintenanceException failed) {
			log.warn("The restore could not switch libraries.", failed);
			forget(confirmed);
			String reopenProblem = reopen();
			return new Outcome(Kind.RESTORE, false, failed.getMessage()
					+ (reopenProblem == null ? "" : "\n\n但图库没能重新打开：" + reopenProblem), null, server.running());
		}
		stage("正在打开恢复后的图库…", -1, false);
		String problem = openRestored(confirmed);
		forget(confirmed);
		if (problem == null) {
			return new Outcome(Kind.RESTORE, true, "已从备份恢复整个图库。\n恢复前的图库原样保留在：" + previous
					+ "\n确认不再需要后，可以自己删除这个文件夹。", previous, true);
		}
		log.warn("The restored library did not open: {}", problem);
		stage("恢复后的图库没能打开，正在退回恢复前的图库…", -1, false);
		if (server.running()) {
			server.stop();
		}
		try {
			LibraryRestore.rollBack(home, confirmed.id());
		} catch (MaintenanceException stuck) {
			log.error("Rolling back the restore failed.", stuck);
			return new Outcome(Kind.RESTORE, false, "恢复后的图库没能打开（" + problem + "）。\n" + stuck.getMessage(), null, false);
		}
		String reopenProblem = reopen();
		return new Outcome(Kind.RESTORE, false, "恢复后的图库没能打开（" + problem + "），已退回恢复前的图库"
				+ (reopenProblem == null ? "，可以继续使用。" : "。\n但恢复前的图库也没能打开：" + reopenProblem), null, server.running());
	}

	/** @return null once the restored library runs, holds what the backup listed, and is recorded as kept */
	private String openRestored(LibraryRestore.Candidate confirmed) {
		try {
			server.start();
			LibraryBackup.Summary listed = confirmed.manifest().summary();
			if (server.count("illustration") != listed.works() || server.count("asset") != listed.assets()) {
				return "作品数量与备份不符";
			}
			LibraryRestore.confirm(home, confirmed.id());
			return null;
		} catch (MaintenanceException | RuntimeException failed) {
			return message(failed);
		}
	}

	private synchronized void forget(LibraryRestore.Candidate used) {
		if (candidate == used) {
			candidate = null;
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
			server.start();
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
