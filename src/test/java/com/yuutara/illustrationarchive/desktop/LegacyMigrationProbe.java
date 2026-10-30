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

	public static LegacyMigration.Result execute(DataSource source, Path media, Path target) throws Exception {
		return LegacyMigration.execute(source, media, target, false);
	}
}
