package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.desktop.DesktopServer;
import com.yuutara.illustrationarchive.desktop.LegacyMigrationProbe;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
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

	private final String url = System.getenv("GALLERY_MYSQL_TEST_URL");
	private final String username = System.getenv("GALLERY_MYSQL_TEST_USERNAME");
	private final String password = System.getenv("GALLERY_MYSQL_TEST_PASSWORD");
	private DisposableSchema schema;
	private String schemaUrl;

	/** A schema of its own, migrated up to {@code version} (all of them when null). */
	private DataSource createSchema(String version) {
		Matcher match = Pattern.compile("^jdbc:mysql://[^/]+/(illustration_archive_gallery_test_[0-9]+)(?:\\?.*)?$").matcher(url);
		assertTrue(match.matches(), "Only a dedicated test schema name is allowed.");
		String name = "illustration_archive_migration_test_" + UUID.randomUUID().toString().substring(0, 8);
		schema = new DisposableSchema(new JdbcTemplate(new DriverManagerDataSource(url.replace("/" + match.group(1), "/"), username, password)), name);
		schemaUrl = url.replace("/" + match.group(1), "/" + name);
		return schema.createThen(() -> {
			DataSource created = new DriverManagerDataSource(schemaUrl, username, password);
			var flyway = Flyway.configure().dataSource(created).locations("classpath:db/migration");
			if (version != null) {
				flyway.target(MigrationVersion.fromVersion(version));
			}
			flyway.load().migrate();
			return created;
		});
	}

	@AfterEach
	void dropSchema() {
		if (schema != null) {
			schema.dropIfCreated();
		}
	}

	@Test
	void realDriverTypesArriveUnchangedAndTheSourceIsNotWritten() throws Exception {
		DataSource source = createSchema(null);
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

	/** What Settings does: read the old config file, connect through the real driver, copy a V7 source into {@code incoming}. */
	@Test
	void aV7SourceReadFromItsConfigFileIsUpgradedOnlyInTheCopy() throws Exception {
		DataSource source = createSchema("7");
		JdbcTemplate jdbc = new JdbcTemplate(source);
		Path media = workspace.resolve("media");
		LegacyMigrationProbe.seedV7(jdbc, media);
		var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
		Path config = Files.writeString(workspace.resolve("application-local.properties"), String.join("\n",
				"ILLUSTRATION_ARCHIVE_DB_URL=" + schemaUrl, "ILLUSTRATION_ARCHIVE_DB_USERNAME=" + username,
				"ILLUSTRATION_ARCHIVE_DB_PASSWORD=" + (password == null ? "" : password),
				"ILLUSTRATION_ARCHIVE_STORAGE_ROOT=" + media.toString().replace('\\', '/'), ""));

		Path incoming = Files.createDirectories(workspace.resolve("incoming"));
		var result = LegacyMigrationProbe.prepare(config, incoming);

		assertEquals("7", result.schemaBefore());
		assertEquals("9", result.schemaAfter());
		assertTrue(result.problems().isEmpty(), result.problems().toString());
		JdbcTemplate copied = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:file:"
				+ incoming.resolve("data").resolve("archive") + DesktopServer.H2_COMPATIBILITY, "sa", ""));
		assertEquals(4294967296L, copied.queryForObject("SELECT likes_order FROM x_like_item WHERE id = 31", Long.class));
		assertEquals(LocalDateTime.parse("2026-09-03T01:02:03.456"),
				copied.queryForObject("SELECT discovered_at FROM x_like_item WHERE id = 31", LocalDateTime.class));
		assertEquals(0, copied.queryForObject("SELECT COUNT(*) FROM x_like_sync_account", Integer.class));
		assertTrue(Files.isRegularFile(incoming.resolve("media").resolve("2026-10").resolve("work.png")));
		assertEquals(history, jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"),
				"MySQL stays at V7");
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
				+ " AND table_name = 'x_like_sync_account'", Integer.class));
	}

	/**
	 * R34-C1 / C2 against the real driver and server: a wrong password fails without the password in what the user or
	 * the log sees, and URLs that the driver would accept with a credential, or whose value it would echo, are refused.
	 */
	@Test
	void credentialsNeverReachTheMessageOrTheLogAndUrlCredentialsAreRefused() throws Exception {
		createSchema(null);
		Path media = Files.createDirectories(workspace.resolve("media"));
		Matcher where = Pattern.compile("^jdbc:mysql://([^/]+)/([^?]+)").matcher(schemaUrl);
		assertTrue(where.find());
		String plain = "jdbc:mysql://" + where.group(1) + "/" + where.group(2);
		String wrong = "wrong-password-" + UUID.randomUUID().toString().substring(0, 8);

		String deniedReport = LegacyMigrationProbe.failure(config(plain + "?useSSL=false&allowPublicKeyRetrieval=true", wrong, media),
				Files.createDirectories(workspace.resolve("incoming-1")));
		assertTrue(deniedReport.contains("没能读取旧版图库的数据库"), deniedReport);
		assertTrue(deniedReport.contains("SQL"), "the error type survives for diagnosis: " + deniedReport);
		assertTrue(!deniedReport.contains(wrong), deniedReport);

		String real = password == null ? "" : password;
		int incoming = 2;
		for (String url : new String[] {
				"jdbc:mysql://address=(host=" + where.group(1).split(":")[0] + ")(port=" + where.group(1).split(":")[1]
						+ ")(user=" + username + ")(password = " + real + ")/" + where.group(2),
				plain + "?password= " + real,
				plain + "?%70assword=" + real,
				plain + "?defaultFetchSize=echoed-value-secret",
				plain + "?passwordCharacterEncoding=echoed-value-secret"}) {
			String report = LegacyMigrationProbe.failure(config(url, wrong, media),
					Files.createDirectories(workspace.resolve("incoming-" + incoming++)));
			assertTrue(report.contains("没有连接"), report);
			assertTrue(real.isEmpty() || !report.contains(real), "the URL's password is not repeated");
			assertTrue(!report.contains("echoed-value-secret") && !report.contains(wrong), report);
		}
	}

	private Path config(String url, String configuredPassword, Path media) throws Exception {
		return Files.writeString(workspace.resolve("application-local.properties"), String.join("\n",
				"ILLUSTRATION_ARCHIVE_DB_URL=" + url, "ILLUSTRATION_ARCHIVE_DB_USERNAME=" + username,
				"ILLUSTRATION_ARCHIVE_DB_PASSWORD=" + configuredPassword,
				"ILLUSTRATION_ARCHIVE_STORAGE_ROOT=" + media.toString().replace('\\', '/'), ""));
	}
}
