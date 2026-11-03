package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Replaces the whole library with a backup, so that whatever happens the user is left with either the library from
 * the backup or the one that was there before: never a mix, and never a new empty one.
 *
 * <p>{@link #prepare} unpacks and checks a backup in {@code <home>/recovery/<id>/incoming} while the library stays in
 * use. That folder is on the same disk as {@code data} and {@code media}, so {@link #switchTo} is four renames. They
 * cannot be one atomic step, so the journal is written before and after each, and {@link #recover} finishes undoing an
 * interrupted switch, from the journal and the folders actually there, before anything opens the database. Where the
 * two disagree it stops rather than guess.
 *
 * <p>The replaced library stays in {@code <home>/recovery/<id>/previous}; the app never deletes it. {@code config} and
 * everything else in the home are never touched.
 */
final class LibraryRestore {

	static final String RECOVERY = "recovery";
	private static final String JOURNAL = "journal.properties";
	private static final Pattern ID = Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");
	private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Logger log = LoggerFactory.getLogger(LibraryRestore.class);

	enum State { PREPARING, READY, SWITCHING, SWITCHED, DONE, ROLLING_BACK, ROLLED_BACK }

	/** A checked backup, unpacked and waiting to replace the library. */
	record Candidate(String id, String fileName, LibraryBackup.Manifest manifest, String schemaBefore, String schemaAfter) {
	}

	private record Move(Path from, Path to) {
	}

	private LibraryRestore() {
	}

	/**
	 * Unpacks and checks a backup beside the library, which stays as it is and may stay in use. A backup at an older
	 * schema is upgraded here, in the unpacked copy; the ZIP is only read.
	 */
	static Candidate prepare(Path home, Path backup, LibraryBackup.Progress progress) throws MaintenanceException {
		Path recovery = recoveryFolder(home);
		String id = ID_TIME.format(LocalDateTime.now()) + "-" + HexFormat.of().toHexDigits((short) RANDOM.nextInt());
		Path operation = recovery.resolve(id);
		boolean ready = false;
		try {
			Files.createDirectory(operation);
			Journal journal = new Journal(operation);
			journal.write(State.PREPARING, 0);
			Path incoming = Files.createDirectory(operation.resolve("incoming"));
			Files.createDirectory(operation.resolve("previous"));
			LibraryBackup.Manifest manifest = LibraryBackup.read(backup, incoming, progress, "正在解包并校验备份…", 0, 0.85);
			Files.createDirectories(incoming.resolve("media"));
			progress.update("正在检查备份里的数据库…", 0.9);
			String[] schema = check(incoming.resolve("data"), manifest);
			if (progress.cancelled()) {
				throw MaintenanceException.cancelled();
			}
			journal.write(State.READY, 0);
			ready = true;
			return new Candidate(id, backup.getFileName().toString(), manifest, schema[0], schema[1]);
		} catch (IOException failed) {
			throw new MaintenanceException("准备恢复时出错：" + LibraryBackup.reason(failed), failed);
		} finally {
			if (!ready) {
				deleteTree(operation);
			}
		}
	}

	/** @return the backup's schema version and the one it now has */
	private static String[] check(Path data, LibraryBackup.Manifest manifest) throws MaintenanceException {
		try {
			// H2 is only on the runtime class path; its JDBC driver says which version this app carries.
			Driver h2 = DriverManager.getDriver("jdbc:h2:");
			String current = h2.getMajorVersion() + "." + h2.getMinorVersion();
			if (!manifest.h2Version().startsWith(current + ".")) {
				throw new MaintenanceException("这个备份的数据库格式（H2 " + manifest.h2Version() + "）与当前版本（H2 " + current
						+ "）不同，暂时不能恢复。");
			}
			try (Connection connection = LibraryBackup.open(data)) {
				if (!LibraryBackup.facts(connection).migrations().equals(manifest.migrations())) {
					throw mismatch("数据库的升级记录与清单不符");
				}
			}
			Flyway flyway = Flyway.configure().dataSource(LibraryBackup.url(data), "sa", "")
					.locations("classpath:db/migration-h2").load();
			for (MigrationInfo info : flyway.info().all()) {
				MigrationState state = info.getState();
				if (state.isFailed()) {
					throw mismatch("备份里的数据库有一次没有完成的升级");
				}
				if (state.isApplied() && !state.isResolved()) {
					throw new MaintenanceException("这个备份来自更新版本的插画档案（数据库版本 V" + info.getVersion()
							+ "），请先升级本应用再恢复。");
				}
			}
			// Checks every applied migration's checksum against this app's before running the missing ones.
			flyway.migrate();
			try (Connection connection = LibraryBackup.open(data)) {
				LibraryBackup.Facts facts = LibraryBackup.facts(connection);
				LibraryBackup.Summary listed = manifest.summary();
				LibraryBackup.Summary found = facts.counts();
				if (found.works() != listed.works() || found.assets() != listed.assets() || found.authors() != listed.authors()
						|| found.tags() != listed.tags() || found.inbox() != listed.inbox()) {
					throw mismatch("数据库里的作品数量与清单不符");
				}
				Map<String, String> files = new HashMap<>();
				manifest.entries().forEach(entry -> files.put(entry.path(), entry.sha256()));
				for (LibraryBackup.Asset asset : facts.assets()) {
					String sha256 = files.get(LibraryBackup.MEDIA + asset.storageKey());
					if (sha256 == null || asset.sha256() != null && !asset.sha256().equalsIgnoreCase(sha256)) {
						throw mismatch("缺少作品引用的原图 " + asset.storageKey());
					}
				}
				return new String[] {latest(manifest.migrations()), latest(facts.migrations())};
			}
		} catch (FlywayException conflicting) {
			throw new MaintenanceException("备份里的数据库结构与当前版本不一致，不能恢复。", conflicting);
		} catch (SQLException unreadable) {
			throw new MaintenanceException("备份里的数据库无法打开：" + unreadable.getMessage(), unreadable);
		}
	}

	private static MaintenanceException mismatch(String detail) {
		return new MaintenanceException("这个备份不完整或已被改动（" + detail + "），不能恢复。");
	}

	private static String latest(List<LibraryBackup.Migration> migrations) {
		return migrations.stream().map(migration -> MigrationVersion.fromVersion(migration.version()))
				.max(Comparator.naturalOrder()).map(MigrationVersion::getVersion).orElse("0");
	}

	/** Drops a prepared backup that will not be used. */
	static void discard(Path home, String id) {
		Path operation = operation(home, id);
		try {
			State state = new Journal(operation).load().state();
			if (state == State.PREPARING || state == State.READY) {
				deleteTree(operation);
			}
		} catch (IOException | MaintenanceException unreadable) {
			log.warn("Could not discard the prepared restore {}.", operation, unreadable);
		}
	}

	/**
	 * Puts a prepared backup in the library's place. The library must be closed. If a rename fails, the ones made
	 * are undone before this throws.
	 *
	 * @return the folder now holding the library that was replaced
	 */
	static Path switchTo(Path home, String id) throws MaintenanceException {
		Path operation = operation(home, id);
		List<Move> moves = moves(home, operation);
		Journal journal = new Journal(operation);
		try {
			journal.load();
		} catch (IOException unreadable) {
			throw new MaintenanceException("读不到这份备份的准备记录，没有恢复。", unreadable);
		}
		if (journal.state() != State.READY) {
			throw new MaintenanceException("这份备份没有准备好，没有恢复。");
		}
		for (int step = 0; step < moves.size(); step++) {
			journal.present(step, Files.exists(moves.get(step).from(), LinkOption.NOFOLLOW_LINKS));
		}
		if (!journal.present(2) || !journal.present(3)) {
			throw new MaintenanceException("暂存的备份不完整，没有恢复。");
		}
		// Counted here rather than read back from the folders: a rename that threw did not happen.
		int done = 0;
		try {
			journal.write(State.SWITCHING, 0);
			for (int step = 0; step < moves.size(); step++) {
				if (journal.present(step)) {
					Files.move(moves.get(step).from(), moves.get(step).to());
				}
				done = step + 1;
				journal.write(State.SWITCHING, done);
			}
			journal.write(State.SWITCHED, done);
			return operation.resolve("previous");
		} catch (IOException failed) {
			log.warn("Switching to the restored library failed; undoing.", failed);
			undo(operation, moves, journal, done);
			throw new MaintenanceException("替换图库时出错，已退回恢复前的图库：" + LibraryBackup.reason(failed)
					+ "。可能有文件正被其他程序占用。", failed);
		}
	}

	/** The restored library opened and checked out: from now on it stays. */
	static void confirm(Path home, String id) throws MaintenanceException {
		try {
			Journal journal = new Journal(operation(home, id)).load();
			if (journal.state() == State.SWITCHED) {
				journal.write(State.DONE, journal.done());
			}
		} catch (IOException failed) {
			throw new MaintenanceException("没能记录恢复结果：" + LibraryBackup.reason(failed), failed);
		}
	}

	/**
	 * Undoes a switch that was started, from wherever it stopped, and puts the library that was there back.
	 * Does nothing for an operation that never started switching or already ended.
	 */
	static void rollBack(Path home, String id) throws MaintenanceException {
		Path operation = operation(home, id);
		List<Move> moves = moves(home, operation);
		Journal journal = new Journal(operation);
		try {
			journal.load();
		} catch (IOException unreadable) {
			throw new MaintenanceException("无法读取恢复记录 " + operation.resolve(JOURNAL) + "。为避免覆盖数据，应用不会继续打开图库。",
					unreadable);
		}
		State state = journal.state();
		if (state != State.SWITCHING && state != State.SWITCHED && state != State.ROLLING_BACK) {
			return;
		}
		undo(operation, moves, journal, settle(moves, journal, journal.done(), state != State.ROLLING_BACK));
	}

	/** Moves back, newest first, the {@code done} forward moves that are in effect. */
	private static void undo(Path operation, List<Move> moves, Journal journal, int done) throws MaintenanceException {
		try {
			journal.write(State.ROLLING_BACK, done);
			for (int step = done - 1; step >= 0; step--) {
				if (journal.present(step)) {
					Files.move(moves.get(step).to(), moves.get(step).from());
				}
				journal.write(State.ROLLING_BACK, step);
			}
			journal.write(State.ROLLED_BACK, 0);
		} catch (IOException failed) {
			throw new MaintenanceException("没能退回恢复前的图库：" + LibraryBackup.reason(failed)
					+ "。为避免覆盖数据，应用不会继续打开图库。请保留文件夹 " + operation + " 并查看日志。", failed);
		}
		// Only the unpacked copy of the backup, which the ZIP still holds.
		deleteTree(operation.resolve("incoming"));
	}

	/**
	 * Finds out whether the rename the journal was about to make (or undo) happened, from the folders themselves.
	 *
	 * @return how many forward moves are in effect
	 */
	private static int settle(List<Move> moves, Journal journal, int done, boolean forward) throws MaintenanceException {
		int step = forward ? done : done - 1;
		if (step < 0 || step >= moves.size() || !journal.present(step)) {
			return done;
		}
		Move move = moves.get(step);
		boolean from = Files.exists(move.from(), LinkOption.NOFOLLOW_LINKS);
		boolean to = Files.exists(move.to(), LinkOption.NOFOLLOW_LINKS);
		if (from == to) {
			throw new MaintenanceException("恢复记录与实际文件对不上（" + move.from() + " → " + move.to()
					+ "）。为避免覆盖数据，应用不会继续打开图库。请保留文件夹 " + move.to().getParent() + " 并查看日志。");
		}
		if (forward) {
			return to ? done + 1 : done;
		}
		return from ? done - 1 : done;
	}

	/**
	 * Runs before anything opens the database. A switch that did not finish, or a restored library that never opened,
	 * is undone; an unpacked backup that was never used is dropped.
	 *
	 * @return a note for the user when a restore was undone, otherwise null
	 */
	static String recover(Path home) throws MaintenanceException {
		Path recovery = home.resolve(RECOVERY);
		if (!Files.isDirectory(recovery, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		List<Path> operations;
		try (Stream<Path> listed = Files.list(recovery)) {
			operations = listed.filter(path -> ID.matcher(path.getFileName().toString()).matches()).sorted().toList();
		} catch (IOException unreadable) {
			throw new MaintenanceException("无法读取恢复记录：" + LibraryBackup.reason(unreadable), unreadable);
		}
		String note = null;
		for (Path operation : operations) {
			State state;
			try {
				state = new Journal(operation).load().state();
			} catch (NoSuchFileException gone) {
				continue;
			} catch (IOException unreadable) {
				throw new MaintenanceException("无法读取恢复记录 " + operation.resolve(JOURNAL) + "："
						+ LibraryBackup.reason(unreadable), unreadable);
			}
			String id = operation.getFileName().toString();
			switch (state) {
				case PREPARING, READY -> discard(home, id);
				case SWITCHING, SWITCHED, ROLLING_BACK -> {
					rollBack(home, id);
					note = "上次从备份恢复没有完成，已回到恢复前的图库。";
				}
				default -> { }
			}
		}
		return note;
	}

	private static Path recoveryFolder(Path home) throws MaintenanceException {
		Path folder = home.resolve(RECOVERY);
		try {
			Files.createDirectories(folder);
			// A link or junction here would send the renames off the library's disk or out of its folder.
			if (!folder.toRealPath().equals(home.toRealPath().resolve(RECOVERY))) {
				throw new MaintenanceException("恢复用的文件夹不能是链接：" + folder);
			}
			return folder;
		} catch (IOException failed) {
			throw new MaintenanceException("无法使用恢复用的文件夹 " + folder + "：" + LibraryBackup.reason(failed), failed);
		}
	}

	/** The id comes back from the page, so it is checked before it becomes part of a path. */
	static Path operation(Path home, String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("Unknown restore operation.");
		}
		return home.resolve(RECOVERY).resolve(id);
	}

	/** The library out, the backup in; the first two are skipped when the library had no such folder. */
	private static List<Move> moves(Path home, Path operation) {
		return List.of(new Move(home.resolve("data"), operation.resolve("previous").resolve("data")),
				new Move(home.resolve("media"), operation.resolve("previous").resolve("media")),
				new Move(operation.resolve("incoming").resolve("data"), home.resolve("data")),
				new Move(operation.resolve("incoming").resolve("media"), home.resolve("media")));
	}

	static void deleteTree(Path root) {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> tree = Files.walk(root)) {
			for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException failed) {
			log.warn("Could not delete {}.", root, failed);
		}
	}

	/**
	 * The switch's record. Each write replaces the whole file in one rename after it reached the disk, so a crash
	 * leaves either the previous version or the new one.
	 */
	private static final class Journal {

		private final Path file;
		private final Properties values = new Properties();

		Journal(Path operation) {
			this.file = operation.resolve(JOURNAL);
		}

		Journal load() throws IOException, MaintenanceException {
			try (InputStream input = Files.newInputStream(file)) {
				values.load(input);
			}
			try {
				State.valueOf(values.getProperty("state"));
				Integer.parseInt(values.getProperty("done"));
			} catch (RuntimeException unreadable) {
				throw new MaintenanceException("恢复记录已损坏：" + file + "。为避免覆盖数据，应用不会继续打开图库。");
			}
			return this;
		}

		State state() {
			return State.valueOf(values.getProperty("state"));
		}

		int done() {
			return Integer.parseInt(values.getProperty("done"));
		}

		boolean present(int step) {
			return Boolean.parseBoolean(values.getProperty("present." + step, "true"));
		}

		void present(int step, boolean present) {
			values.setProperty("present." + step, Boolean.toString(present));
		}

		void write(State state, int done) throws IOException {
			values.setProperty("state", state.name());
			values.setProperty("done", Integer.toString(done));
			Path next = file.resolveSibling(JOURNAL + ".next");
			try (FileChannel channel = FileChannel.open(next, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				OutputStream output = Channels.newOutputStream(channel);
				values.store(output, null);
				output.flush();
				channel.force(true);
			}
			Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}
}
