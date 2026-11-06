package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The backup a new version makes before it upgrades a library, on real files and a file-based H2 without Spring. */
class UpgradeBackupTest {

	private static final LibraryBackup.Progress QUIET = (stage, fraction) -> { };
	private static final ClassLoader LOADER = UpgradeBackupTest.class.getClassLoader();

	@TempDir
	Path home;

	@Test
	void anOlderLibraryIsBackedUpCompletelyAndLeftAsItWas() throws Exception {
		JdbcTemplate seven = library("7");

		Path backup = UpgradeBackup.ifNeeded(home, "0.6.1", QUIET).orElseThrow();

		assertEquals(home.resolve("backups"), backup.getParent());
		assertTrue(backup.getFileName().toString().startsWith("插画档案备份-升级前-"), backup.toString());
		LibraryBackup.Manifest manifest = LibraryBackup.read(backup, null, QUIET, "", 0, 1);
		assertEquals("7", manifest.migrations().get(manifest.migrations().size() - 1).version(), "the backup is of the library before the upgrade");
		assertEquals(1, manifest.summary().works());
		try (ZipFile zip = new ZipFile(backup.toFile())) {
			assertTrue(zip.getEntry("media/2026-10/work.png") != null, "the originals are in it");
		}
		assertEquals("7", latest(seven), "making the backup does not upgrade the library");
		try (Stream<Path> files = Files.list(backup.getParent())) {
			assertEquals(List.of(backup), files.toList(), "no temporary file is left");
		}
	}

	@Test
	void aCurrentLibraryOrNoLibraryGetsNoBackup() throws Exception {
		assertEquals(Optional.empty(), UpgradeBackup.ifNeeded(home, "0.6.1", QUIET), "a first start has nothing to back up");
		assertFalse(Files.exists(home.resolve("data").resolve("archive.mv.db")), "checking creates no database");

		JdbcTemplate seven = library("7");
		assertTrue(UpgradeBackup.needsUpgrade(home, LOADER));
		Flyway.configure().dataSource(seven.getDataSource()).locations("classpath:db/migration-h2").load().migrate();

		assertFalse(UpgradeBackup.needsUpgrade(home, LOADER));
		assertEquals(Optional.empty(), UpgradeBackup.ifNeeded(home, "0.6.1", QUIET));
		assertFalse(Files.exists(home.resolve("backups")));
	}

	@Test
	void aBackupThatCannotBeCompletedStopsTheUpgradeAndLeavesNothing() throws Exception {
		JdbcTemplate seven = library("7");
		Files.delete(home.resolve("media").resolve("2026-10").resolve("work.png"));

		MaintenanceException failed = assertThrows(MaintenanceException.class, () -> UpgradeBackup.ifNeeded(home, "0.6.1", QUIET));

		assertTrue(failed.getMessage().contains("2026-10/work.png"), failed.getMessage());
		try (Stream<Path> files = Files.list(home.resolve("backups"))) {
			assertEquals(0, files.count(), "a failed backup leaves no file that looks like one");
		}
		assertEquals("7", latest(seven));
	}

	@Test
	void anUpgradeBackupKeptInTheHomeIsNotPartOfLaterBackups() throws Exception {
		JdbcTemplate seven = library("7");
		UpgradeBackup.ifNeeded(home, "0.6.1", QUIET).orElseThrow();
		Flyway.configure().dataSource(seven.getDataSource()).locations("classpath:db/migration-h2").load().migrate();

		Path later = Files.createDirectories(home.resolveSibling(home.getFileName() + "-out")).resolve("later.zip");
		try {
			LibraryBackup.create(home, later, "0.6.1", QUIET);
			try (ZipFile zip = new ZipFile(later.toFile())) {
				assertTrue(zip.stream().noneMatch(entry -> entry.getName().startsWith(UpgradeBackup.FOLDER)), "backups stay out");
			}
		} finally {
			Files.deleteIfExists(later);
			Files.deleteIfExists(later.getParent());
		}
	}

	@Test
	void namesSortByTime() {
		assertEquals("插画档案备份-升级前-20261106-093005.zip", UpgradeBackup.name(LocalDateTime.of(2026, 11, 6, 9, 30, 5)));
	}

	/** A closed library at schema {@code version}, with one work and its original. */
	private JdbcTemplate library(String version) throws Exception {
		String url = "jdbc:h2:file:" + home.resolve("data").resolve("archive") + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_ON_EXIT=FALSE";
		DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
		Flyway.configure().dataSource(source).locations("classpath:db/migration-h2").target(version).load().migrate();
		JdbcTemplate jdbc = new JdbcTemplate(source);
		LegacyMigrationTest.seedArchive(jdbc, Files.createDirectories(home.resolve("media")), false);
		return jdbc;
	}

	private static String latest(JdbcTemplate jdbc) {
		return jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
				String.class);
	}
}
