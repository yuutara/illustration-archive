package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.desktop.DesktopServer;
import com.yuutara.illustrationarchive.desktop.LegacyMigrationProbe;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real driver's types (TIMESTAMP, DATETIME(3), TEXT, BINARY, TINYINT booleans) crossing into H2. Opt-in, and it
 * only ever touches a schema it created itself; see {@link DisposableSchema}.
 */
@EnabledIfEnvironmentVariable(named = "GALLERY_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class LegacyMigrationMySqlTest {

	@TempDir
	Path workspace;

	private DisposableSchema schema;
	private DataSource source;

	@BeforeEach
	void createSchema() {
		String url = System.getenv("GALLERY_MYSQL_TEST_URL");
		String username = System.getenv("GALLERY_MYSQL_TEST_USERNAME");
		String password = System.getenv("GALLERY_MYSQL_TEST_PASSWORD");
		var match = Pattern.compile("^jdbc:mysql://[^/]+/(illustration_archive_gallery_test_[0-9]+)(?:\\?.*)?$").matcher(url);
		assertTrue(match.matches(), "Only a dedicated test schema name is allowed.");
		String name = "illustration_archive_migration_test_" + UUID.randomUUID().toString().substring(0, 8);
		schema = new DisposableSchema(new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + match.group(1), "/"), username, password)), name);
		source = schema.createThen(() -> {
			DataSource created = new DriverManagerDataSource(url.replace("/" + match.group(1), "/" + name), username, password);
			Flyway.configure().dataSource(created).locations("classpath:db/migration").load().migrate();
			return created;
		});
	}

	@AfterEach
	void dropSchema() {
		schema.dropIfCreated();
	}

	@Test
	void realDriverTypesArriveUnchangedAndTheSourceIsNotWritten() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(source);
		Path media = workspace.resolve("media");
		LegacyMigrationProbe.seed(jdbc, media);
		long sourceRows = jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Long.class);

		Path target = workspace.resolve("new-home");
		var result = LegacyMigrationProbe.execute(source, media, target);

		assertEquals(1L, result.rows().get("x_like_sync_seen_token"));
		assertTrue(result.problems().isEmpty(), result.problems().toString());
		JdbcTemplate copied = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:"
				+ target.resolve("data").resolve("archive") + DesktopServer.H2_COMPATIBILITY, "sa", ""));
		assertEquals(LocalDateTime.parse("2026-09-01T08:30:00"),
				copied.queryForObject("SELECT created_at FROM illustration WHERE id = 11", LocalDateTime.class));
		assertEquals(LocalDateTime.parse("2026-09-03T01:02:03.456"),
				copied.queryForObject("SELECT discovered_at FROM x_like_item WHERE id = 31", LocalDateTime.class));
		assertEquals("正文", copied.queryForObject("SELECT post_text FROM x_like_item WHERE id = 31", String.class));
		assertEquals(Boolean.TRUE, copied.queryForObject("SELECT coverage_to_end FROM x_like_sync_state WHERE id = 2", Boolean.class));
		assertArrayEquals(new byte[32], copied.queryForObject("SELECT token_sha256 FROM x_like_sync_seen_token", byte[].class));
		assertEquals(sourceRows, jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Long.class));
	}
}
