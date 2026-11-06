package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * A backup made before a new version upgrades the library's schema. The upgrade runs when the server starts, and a
 * database transaction cannot undo it, so the launcher calls this first and starts the server only once the backup is
 * written in full and read back ({@link LibraryBackup#createAt}). A library that needs no upgrade, or a home without
 * a library yet, gets no backup.
 *
 * <p>Backups go to {@code <home>/backups}, next to the library rather than in a folder the user picks: nobody is asked
 * anything at start-up, and the backup is for undoing an upgrade, not for losing the disk. Nothing there is ever
 * deleted by the app; a backup is a normal one and restores from Settings.
 */
final class UpgradeBackup {

	static final String FOLDER = "backups";
	private static final String MIGRATIONS = "classpath:db/migration-h2";
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Logger log = LoggerFactory.getLogger(UpgradeBackup.class);

	private UpgradeBackup() {
	}

	/** @return the new backup, or empty when the library needs no upgrade */
	static Optional<Path> ifNeeded(Path home, String appVersion, LibraryBackup.Progress progress)
			throws MaintenanceException {
		return needsUpgrade(home, UpgradeBackup.class.getClassLoader())
				? Optional.of(create(home, appVersion, progress)) : Optional.empty();
	}

	/**
	 * Backs the closed library up into {@code <home>/backups}. A failure or a cancel through {@code progress} leaves no
	 * file there that looks like a backup, and the library as it was.
	 */
	static Path create(Path home, String appVersion, LibraryBackup.Progress progress) throws MaintenanceException {
		Path target;
		try {
			target = Files.createDirectories(home.resolve(FOLDER)).resolve(name(LocalDateTime.now()));
		} catch (IOException unwritable) {
			throw new MaintenanceException("无法创建备份文件夹：" + LibraryBackup.reason(unwritable), unwritable);
		}
		LibraryBackup.Manifest manifest = LibraryBackup.createAt(home, target, appVersion, progress);
		log.info("Backed up the library before upgrading it: {} ({} works, {} files)", target,
				manifest.summary().works(), manifest.summary().files());
		return target;
	}

	static String name(LocalDateTime time) {
		return "插画档案备份-升级前-" + FILE_TIME.format(time) + ".zip";
	}

	/**
	 * Whether starting this version would apply a migration to an existing library. The database is opened read-only,
	 * so the file stays byte for byte as it was.
	 *
	 * @throws MaintenanceException when the database cannot be read at all, which is a library that does not open
	 */
	static boolean needsUpgrade(Path home, ClassLoader loader) throws MaintenanceException {
		Path data = home.resolve("data");
		if (!Files.isRegularFile(data.resolve("archive.mv.db"))) {
			return false;
		}
		try {
			Flyway flyway = Flyway.configure(loader).dataSource(LibraryBackup.readOnlyUrl(data), "sa", "").locations(MIGRATIONS).load();
			return flyway.info().pending().length > 0;
		} catch (FlywayException unreadable) {
			throw new MaintenanceException("没能读取图库的版本，所以没有升级：" + unreadable.getMessage(), unreadable);
		}
	}
}
