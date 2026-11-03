package com.yuutara.illustrationarchive.desktop;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

/** Package bridge for the MySQL test, which lives beside {@code DisposableSchema} in the repository package. */
public final class LegacyMigrationProbe {

	private LegacyMigrationProbe() {
	}

	public static void seed(JdbcTemplate jdbc, Path media) throws Exception {
		Files.createDirectories(media);
		LegacyMigrationTest.seedArchive(jdbc, media);
	}

	public static void seedV7(JdbcTemplate jdbc, Path media) throws Exception {
		Files.createDirectories(media);
		LegacyMigrationTest.seedArchive(jdbc, media, false);
	}

	public static LegacyMigration.Result execute(DataSource source, Path media, Path target) throws Exception {
		return LegacyMigration.execute(source, media, target, false);
	}

	/**
	 * Reads the config and copies the way Settings does, expecting it to fail.
	 *
	 * @return everything the failure says, its message and the whole cause chain as the log would print it
	 */
	public static String failure(Path config, Path incoming) {
		try {
			prepare(config, incoming);
		} catch (MaintenanceException failed) {
			java.io.StringWriter printed = new java.io.StringWriter();
			failed.printStackTrace(new java.io.PrintWriter(printed));
			return printed.toString();
		} catch (Exception unexpected) {
			throw new AssertionError("expected a refusal the app explains", unexpected);
		}
		throw new AssertionError("expected the import to fail");
	}

	/** Reads the config the way Settings does and copies into {@code incoming}. */
	public static LegacyMigration.Result prepare(Path config, Path incoming) throws Exception {
		LegacyMigration.Source source = LegacyMigration.Source.read(config);
		return LegacyMigration.prepare(source, source.mediaRoot().orElseThrow(), incoming, (stage, fraction) -> { });
	}
}
