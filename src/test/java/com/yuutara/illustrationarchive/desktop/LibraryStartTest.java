package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start thread's order of things on a real, closed V7 library: version, backup, then the server (which upgrades),
 * and how quitting at each point keeps the upgrade from happening. The server start is recorded, not run; the
 * launcher's own wiring and windows are checked in the packaged app.
 */
class LibraryStartTest {

	private static final ClassLoader LOADER = LibraryStartTest.class.getClassLoader();

	@TempDir
	Path home;

	private final AtomicBoolean quitting = new AtomicBoolean();
	private final List<String> events = new CopyOnWriteArrayList<>();

	/** Real version check and backup; the rest records what the launcher would be told. */
	private class Recorded implements LibraryStart.Steps {

		@Override
		public boolean needsUpgrade() throws MaintenanceException {
			return UpgradeBackup.needsUpgrade(home, LOADER);
		}

		@Override
		public void backUp(LibraryBackup.Progress progress) throws MaintenanceException {
			events.add("backup");
			UpgradeBackup.create(home, "0.6.1", progress);
		}

		@Override
		public void progress(String stage, double fraction) {
		}

		@Override
		public void backedUp() {
			events.add("backed up");
		}

		@Override
		public void startServer() {
			events.add("server");
		}

		@Override
		public void unreadable(String message) {
			events.add("unreadable: " + message);
		}

		@Override
		public void backupFailed(String message) {
			events.add("backup failed: " + message);
		}
	}

	private void run(LibraryStart.Steps steps) {
		new LibraryStart(steps, quitting::get).run(true);
	}

	@Test
	void anOlderLibraryIsBackedUpBeforeTheServerStarts() throws Exception {
		UpgradeBackupTest.library(home, "7");

		run(new Recorded());

		assertEquals(List.of("backup", "backed up", "server"), events);
		assertEquals(1, backups().size());
	}

	@Test
	void quittingWhileTheBackupIsWrittenStopsItLeavesNothingAndNeverUpgrades() throws Exception {
		UpgradeBackupTest.library(home, "7");
		String before = UpgradeBackupTest.databaseHash(home);

		run(new Recorded() {
			@Override
			public void progress(String stage, double fraction) {
				if (stage.startsWith("正在打包")) {
					quitting.set(true); // The user quits while the files go into the ZIP.
				}
			}
		});

		assertEquals(List.of("backup"), events, "no failure window and no server");
		assertEquals(List.of(), backups(), "neither a backup nor a partial file is left");
		assertEquals(before, UpgradeBackupTest.databaseHash(home));
		assertEquals("7", UpgradeBackupTest.latest(home));
	}

	@Test
	void quittingOnceTheBackupIsDoneKeepsTheBackupAndStillDoesNotUpgrade() throws Exception {
		UpgradeBackupTest.library(home, "7");
		String before = UpgradeBackupTest.databaseHash(home);

		run(new Recorded() {
			@Override
			public void backedUp() {
				super.backedUp();
				quitting.set(true); // Between the finished backup and the server start.
			}
		});

		assertEquals(List.of("backup", "backed up"), events);
		assertEquals(1, backups().size(), "the finished backup stays");
		LibraryBackup.read(backups().get(0), null, (stage, fraction) -> { }, "", 0, 1);
		assertEquals(before, UpgradeBackupTest.databaseHash(home));
	}

	@Test
	void aLibraryThatCannotBeReadGoesStraightToTheRestoreWindow() throws Exception {
		UpgradeBackupTest.library(home, "7");
		Files.write(home.resolve("data").resolve("archive.mv.db"), new byte[64 * 1024]);

		run(new Recorded());

		assertEquals(1, events.size(), events.toString());
		assertTrue(events.get(0).startsWith("unreadable: "), events.toString());
		assertFalse(Files.exists(home.resolve(UpgradeBackup.FOLDER)), "nothing is attempted before the restore");
	}

	@Test
	void aBackupThatFailsAsksAndNeverStartsTheServer() throws Exception {
		UpgradeBackupTest.library(home, "7");
		Files.delete(home.resolve("media").resolve("2026-10").resolve("work.png"));

		run(new Recorded());

		assertEquals(2, events.size(), events.toString());
		assertTrue(events.get(1).startsWith("backup failed: "), events.toString());
		assertEquals("7", UpgradeBackupTest.latest(home));
	}

	@Test
	void aCurrentLibraryNoLibraryAndOpeningWithoutABackupStartAtOnce() throws Exception {
		run(new Recorded());
		assertEquals(List.of("server"), events, "a first start");

		events.clear();
		var seven = UpgradeBackupTest.library(home, "7");
		new LibraryStart(new Recorded(), quitting::get).run(false);
		assertEquals(List.of("server"), events, "the user chose to open without a backup");

		events.clear();
		Flyway.configure().dataSource(seven.getDataSource()).locations("classpath:db/migration-h2").load().migrate();
		run(new Recorded());
		assertEquals(List.of("server"), events, "nothing to upgrade");
		assertFalse(Files.exists(home.resolve(UpgradeBackup.FOLDER)));
	}

	@Test
	void quittingWaitsForAnUpgradeThatHasBegun() throws Exception {
		CountDownLatch migrating = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		LibraryStart start = new LibraryStart(new Recorded() {
			@Override
			public void startServer() {
				migrating.countDown();
				try {
					finish.await(); // Flyway is upgrading the library.
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				events.add("server");
			}
		}, quitting::get);
		start.begin(true, LOADER);
		assertTrue(migrating.await(10, TimeUnit.SECONDS));
		quitting.set(true);

		CountDownLatch ended = new CountDownLatch(1);
		Thread quit = new Thread(() -> {
			start.awaitEnd();
			ended.countDown();
		});
		quit.start();
		assertFalse(ended.await(300, TimeUnit.MILLISECONDS), "the instance lock is not given up mid-upgrade");
		finish.countDown();
		assertTrue(ended.await(10, TimeUnit.SECONDS));
		assertEquals(List.of("server"), events);
	}

	private List<Path> backups() throws Exception {
		Path folder = home.resolve(UpgradeBackup.FOLDER);
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.toList();
		}
	}
}
