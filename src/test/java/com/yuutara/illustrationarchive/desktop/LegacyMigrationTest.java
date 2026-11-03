package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The copy logic against an H2 source; {@code LegacyMigrationMySqlTest} repeats the essentials on real MySQL. */
class LegacyMigrationTest {

	/** Every table a V8 library has, for row-by-row comparison. */
	static final List<String> TABLES = List.of("author", "tag", "illustration", "asset", "illustration_tag",
			"x_like_item", "x_like_media", "x_like_sync_state", "x_like_sync_seen_token", "x_like_sync_account");

	@TempDir
	Path workspace;

	private DataSource source;
	private JdbcTemplate sourceJdbc;
	private Path media;

	@BeforeEach
	void seed() throws Exception {
		source = h2Source(null);
		sourceJdbc = new JdbcTemplate(source);
		media = Files.createDirectories(workspace.resolve("old-media"));
		seedArchive(sourceJdbc, media);
	}

	@AfterEach
	void drop() {
		sourceJdbc.execute("DROP ALL OBJECTS");
	}

	/** An in-memory H2 standing in for the old MySQL, built up to {@code version} (all of them when null). */
	static DataSource h2Source(String version) {
		DataSource created = new DriverManagerDataSource("jdbc:h2:mem:legacy-" + UUID.randomUUID()
				+ DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1", "sa", "");
		var flyway = Flyway.configure().dataSource(created).locations("classpath:db/migration-h2");
		if (version != null) {
			flyway.target(MigrationVersion.fromVersion(version));
		}
		flyway.load().migrate();
		return created;
	}

	static void seedArchive(JdbcTemplate jdbc, Path media) throws Exception {
		seedArchive(jdbc, media, true);
	}

	/** @param account also the sync account row, which only exists from V8 on */
	static void seedArchive(JdbcTemplate jdbc, Path media, boolean account) throws Exception {
		byte[] image = "image-bytes".getBytes(StandardCharsets.UTF_8);
		Path file = media.resolve("2026-10").resolve("work.png");
		Files.createDirectories(file.getParent());
		Files.write(file, image);
		Path thumbnail = media.resolve("thumbnails").resolve("2026-10").resolve("work.png");
		Files.createDirectories(thumbnail.getParent());
		Files.write(thumbnail, "thumb".getBytes(StandardCharsets.UTF_8));
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));

		jdbc.update("INSERT INTO author (id, display_name, x_username, x_user_id) VALUES (5, '雨宫', 'amamiya', '777')");
		jdbc.update("INSERT INTO tag (id, name) VALUES (9, '夜空')");
		jdbc.update("INSERT INTO illustration (id, title, author_id, note, created_at, updated_at)"
				+ " VALUES (11, '标题', 5, '备注', '2026-09-01 08:30:00', '2026-09-02 09:00:00')");
		jdbc.update("INSERT INTO asset (id, illustration_id, original_filename, storage_key, mime_type, file_size, sort_order, sha256)"
				+ " VALUES (21, 11, 'work.png', '2026-10/work.png', 'image/png', ?, 0, ?)", image.length, sha);
		jdbc.update("INSERT INTO illustration_tag (illustration_id, tag_id) VALUES (11, 9)");
		jdbc.update("INSERT INTO x_like_item (id, x_post_id, x_author_id, author_username, author_display_name, post_text,"
				+ " status, discovered_at, updated_at, likes_order, imported_illustration_id)"
				+ " VALUES (31, '1001', '777', 'amamiya', '雨宫', '正文', 'IMPORTED', '2026-09-03 01:02:03.456', '2026-09-03 01:02:03.456', 4294967296, 11)");
		jdbc.update("INSERT INTO x_like_media (id, x_like_item_id, media_key, sort_order, media_type, source_url, width, height)"
				+ " VALUES (41, 31, 'm1', 0, 'photo', 'https://pbs.twimg.com/media/a.jpg', 100, 200)");
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at, matched_count, coverage_to_end)"
				+ " VALUES (2, 'cursor', 25, 'ACTIVE', '2026-09-03 01:02:03', 3, TRUE)");
		jdbc.update("INSERT INTO x_like_sync_seen_token (sync_id, token_sha256) VALUES (2, ?)", (Object) new byte[32]);
		if (account) {
			jdbc.update("INSERT INTO x_like_sync_account (id, owner_x_user_id, updated_at) VALUES (1, '42', '2026-09-03 01:02:03')");
		}
	}

	/** Every row of {@code table}, in a stable order, with values made comparable (bytes as hex, times as wall clock). */
	static List<Map<String, Object>> rows(JdbcTemplate jdbc, String table) {
		return jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2").stream().map(row -> {
			Map<String, Object> plain = new TreeMap<>();
			row.forEach((column, value) -> plain.put(column.toLowerCase(Locale.ROOT), switch (value) {
				case byte[] bytes -> HexFormat.of().formatHex(bytes);
				case Timestamp time -> time.toLocalDateTime();
				case null -> "NULL";
				default -> value;
			}));
			return plain;
		}).toList();
	}

	static Map<String, List<Map<String, Object>>> everyRow(JdbcTemplate jdbc, List<String> tables) {
		Map<String, List<Map<String, Object>>> all = new LinkedHashMap<>();
		tables.forEach(table -> all.put(table, rows(jdbc, table)));
		return all;
	}

	static JdbcTemplate library(Path home) {
		return new JdbcTemplate(new DriverManagerDataSource(LibraryBackup.url(home.resolve("data")), "sa", ""));
	}

	@Test
	void copiesEveryTableWithItsIdsAndEveryFileIntoANewLibrary() throws Exception {
		Path target = workspace.resolve("new-home");
		LegacyMigration.Result result = LegacyMigration.execute(source, media, target, false);

		assertEquals(1L, result.rows().get("illustration"));
		assertEquals(1L, result.rows().get("x_like_sync_seen_token"));
		assertEquals("8", result.schemaBefore());
		assertEquals("8", result.schemaAfter());
		assertEquals(1, result.files());
		assertTrue(result.problems().isEmpty());
		assertEquals("image-bytes", Files.readString(target.resolve("media").resolve("2026-10").resolve("work.png")));
		assertEquals("thumb", Files.readString(target.resolve("media").resolve("thumbnails").resolve("2026-10").resolve("work.png")));

		JdbcTemplate jdbc = library(target);
		assertEquals(everyRow(sourceJdbc, TABLES), everyRow(jdbc, TABLES), "every row of every table, ids included");
		assertEquals(LocalDateTime.parse("2026-09-03T01:02:03.456"),
				jdbc.queryForObject("SELECT discovered_at FROM x_like_item WHERE id = 31", LocalDateTime.class));
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class),
				"copied progress keeps its owner");

		jdbc.update("INSERT INTO author (display_name) VALUES ('新作者')");
		assertEquals(6L, jdbc.queryForObject("SELECT id FROM author WHERE display_name = '新作者'", Long.class),
				"new rows continue after the copied ids");
		assertEquals(1, sourceJdbc.queryForObject("SELECT COUNT(*) FROM illustration", Integer.class), "source untouched");
		assertTrue(Files.exists(media.resolve("2026-10").resolve("work.png")), "source files untouched");
		assertNoStagingLeft();
	}

	@Test
	void aV7SourceIsUpgradedInTheCopyAndNeverInTheSource() throws Exception {
		DataSource v7 = h2Source("7");
		JdbcTemplate v7Jdbc = new JdbcTemplate(v7);
		Path v7Media = Files.createDirectories(workspace.resolve("v7-media"));
		seedArchive(v7Jdbc, v7Media, false);
		List<String> v7Tables = TABLES.subList(0, TABLES.size() - 1);
		var before = everyRow(v7Jdbc, v7Tables);
		var history = v7Jdbc.queryForList("SELECT * FROM flyway_schema_history");
		Path target = workspace.resolve("v7-home");
		try {
			LegacyMigration.Result result = LegacyMigration.execute(v7, v7Media, target, false);

			assertEquals("7", result.schemaBefore());
			assertEquals("8", result.schemaAfter());
			assertFalse(result.rows().containsKey("x_like_sync_account"), "a table V7 does not have is not read");
			JdbcTemplate copied = library(target);
			assertEquals(before, everyRow(copied, v7Tables));
			assertEquals(0, copied.queryForObject("SELECT COUNT(*) FROM x_like_sync_account", Integer.class),
					"V7 progress has no owner yet; the first sync asks");
			assertEquals("8", copied.queryForObject(
					"SELECT MAX(CAST(version AS INT)) FROM flyway_schema_history WHERE success = TRUE", String.class));
			assertEquals(before, everyRow(v7Jdbc, v7Tables), "the source is only read");
			assertEquals(history, v7Jdbc.queryForList("SELECT * FROM flyway_schema_history"), "and is not upgraded");
		} finally {
			v7Jdbc.execute("DROP ALL OBJECTS");
		}
	}

	@Test
	void checkWritesNothingButStillReportsProblems() throws Exception {
		Files.delete(media.resolve("2026-10").resolve("work.png"));
		List<Path> before = tree();

		LegacyMigration.Result result = LegacyMigration.check(source, media);

		assertEquals(1L, result.rows().get("asset"));
		assertEquals(0, result.files());
		assertEquals(List.of("缺少文件：2026-10/work.png"), result.problems());
		assertEquals(before, tree());
	}

	@Test
	void refusesALibraryThatAlreadyExistsAndLeavesItAlone() throws Exception {
		Path target = Files.createDirectories(workspace.resolve("existing"));
		Files.writeString(target.resolve("mine.txt"), "keep me");

		assertThrows(MaintenanceException.class, () -> LegacyMigration.execute(source, media, target, false));

		assertEquals("keep me", Files.readString(target.resolve("mine.txt")));
		assertEquals(1, Files.list(target).count());
		assertNoStagingLeft();
	}

	@Test
	void changedOrMissingFilesStopTheCopyAndLeaveNoTargetUnlessAccepted() throws Exception {
		Files.writeString(media.resolve("2026-10").resolve("work.png"), "tampered");
		Path target = workspace.resolve("new-home");

		MaintenanceException refused = assertThrows(MaintenanceException.class,
				() -> LegacyMigration.execute(source, media, target, false));

		assertTrue(refused.getMessage().contains("SHA-256"), refused.getMessage());
		assertFalse(Files.exists(target));
		assertNoStagingLeft();

		LegacyMigration.Result accepted = LegacyMigration.execute(source, media, target, true);
		assertEquals(1, accepted.problems().size());
		assertTrue(Files.isDirectory(target));
	}

	@Test
	void aStorageKeyCannotReachOutsideTheMediaRoot() throws Exception {
		Files.writeString(workspace.resolve("secret.txt"), "outside");
		sourceJdbc.update("UPDATE asset SET storage_key = '../secret.txt' WHERE id = 21");

		LegacyMigration.Result result = LegacyMigration.check(source, media);

		assertEquals(List.of("存储路径超出了媒体文件夹：../secret.txt"), result.problems());
		assertEquals(0, result.files());
	}

	@Test
	void aNewerFailedOrIncompleteSchemaIsRefusedAndNothingIsLeft() throws Exception {
		Path target = workspace.resolve("new-home");
		sourceJdbc.update("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum,"
				+ " installed_by, execution_time, success) VALUES (99, '9', 'future', 'SQL', 'V9__future.sql', 1, 'sa', 1, TRUE)");
		assertRefused(target, "更新版本");

		sourceJdbc.update("DELETE FROM flyway_schema_history WHERE version = '9'");
		sourceJdbc.update("UPDATE flyway_schema_history SET success = FALSE WHERE version = '8'");
		assertRefused(target, "没有完成的升级");

		sourceJdbc.update("UPDATE flyway_schema_history SET success = TRUE WHERE version = '8'");
		sourceJdbc.update("DELETE FROM flyway_schema_history WHERE version = '5'");
		assertRefused(target, "对不上");

		sourceJdbc.execute("DROP TABLE flyway_schema_history");
		assertRefused(target, "升级记录");
	}

	private void assertRefused(Path target, String reason) {
		MaintenanceException refused = assertThrows(MaintenanceException.class,
				() -> LegacyMigration.execute(source, media, target, false));
		assertTrue(refused.getMessage().contains(reason), refused.getMessage());
		assertFalse(Files.exists(target));
		assertNoStagingLeft();
	}

	@Test
	void notEnoughSpaceForTheMediaIsRefusedBeforeAnythingIsCopied() throws Exception {
		Path home = Files.createDirectories(workspace.resolve("home"));
		sourceJdbc.update("UPDATE asset SET file_size = ? WHERE id = 21", 1L << 56);
		LegacyMigration.Source old = new LegacyMigration.Source("db.example:3306", "archive", media.toString(), source,
				List.of());

		MaintenanceException refused = assertThrows(MaintenanceException.class, () -> LibraryRestore.prepare(home,
				incoming -> LegacyMigration.prepare(old, media, incoming, (stage, fraction) -> { })));

		assertTrue(refused.getMessage().contains("磁盘空间不够"), refused.getMessage());
		try (Stream<Path> operations = Files.list(home.resolve(LibraryRestore.RECOVERY))) {
			assertEquals(0, operations.count(), "no prepared library is left");
		}
	}

	@Test
	void theConfigFileOnlyGivesAMySqlSourceWithLocalMediaAndNeverRepeatsItsSecrets() throws Exception {
		Path config = workspace.resolve("application-local.properties");
		Files.writeString(config, """
				ILLUSTRATION_ARCHIVE_DB_URL=jdbc:mysql://someone:url-pass@db.example:3307/illustration_archive?useSSL=false&password=inurl
				ILLUSTRATION_ARCHIVE_DB_USERNAME=someone
				ILLUSTRATION_ARCHIVE_DB_PASSWORD=do-not-print-me
				ILLUSTRATION_ARCHIVE_STORAGE_ROOT=storage
				X_CLIENT_SECRET=not-read
				""");

		LegacyMigration.Source read = LegacyMigration.Source.read(config);

		assertEquals("db.example:3307 / illustration_archive", read.describe());
		assertEquals("storage", read.configuredMediaRoot());
		assertTrue(read.mediaRoot().isEmpty(), "a relative media folder is not guessed");
		String redacted = read.redact("x do-not-print-me y ?useSSL=false&password=inurl z someone:url-pass@ password=other");
		assertFalse(redacted.contains("do-not-print-me"), redacted);
		assertFalse(redacted.contains("inurl"), redacted);
		assertFalse(redacted.contains("url-pass"), redacted);
		assertFalse(redacted.contains("other"), redacted);
		assertFalse(read.toString().contains("someone"), read.toString());

		String rest = "ILLUSTRATION_ARCHIVE_DB_USERNAME=u\nILLUSTRATION_ARCHIVE_STORAGE_ROOT=D:/media\n";
		Map<String, String> refusals = Map.of(
				"ILLUSTRATION_ARCHIVE_DB_URL=jdbc:h2:file:./data\n" + rest, "不是 MySQL",
				"ILLUSTRATION_ARCHIVE_DB_URL=jdbc:mysql://db/a\nILLUSTRATION_ARCHIVE_STORAGE_TYPE=s3\n" + rest, "S3",
				"ILLUSTRATION_ARCHIVE_DB_URL=jdbc:mysql://db/a\nILLUSTRATION_ARCHIVE_DB_USERNAME=u\n", "STORAGE_ROOT");
		for (Map.Entry<String, String> refusal : refusals.entrySet()) {
			Files.writeString(config, refusal.getKey());
			MaintenanceException refused = assertThrows(MaintenanceException.class, () -> LegacyMigration.Source.read(config));
			assertTrue(refused.getMessage().contains(refusal.getValue()), refused.getMessage());
		}
	}

	@Test
	void commandReportsWithoutEverPrintingThePassword() throws Exception {
		Path config = workspace.resolve("application-local.properties");
		Files.writeString(config, """
				ILLUSTRATION_ARCHIVE_DB_URL=jdbc:mysql://127.0.0.1:1/none?password=inurl
				ILLUSTRATION_ARCHIVE_DB_USERNAME=someone
				ILLUSTRATION_ARCHIVE_DB_PASSWORD=do-not-print-me
				ILLUSTRATION_ARCHIVE_STORAGE_ROOT=%s
				""".formatted(media.toString().replace('\\', '/')));
		var bytes = new java.io.ByteArrayOutputStream();

		int exit = LegacyMigration.run(new String[] {LegacyMigration.COMMAND, "--config=" + config}, new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8));

		String output = bytes.toString(StandardCharsets.UTF_8);
		assertEquals(1, exit, output);
		assertTrue(output.contains("Check only"), output);
		assertFalse(output.contains("do-not-print-me"), output);
		assertFalse(output.contains("inurl"), output);
	}

	private void assertNoStagingLeft() {
		try (Stream<Path> entries = Files.list(workspace)) {
			assertTrue(entries.noneMatch(path -> path.getFileName().toString().contains(".migrating-")));
		} catch (java.io.IOException failed) {
			throw new IllegalStateException(failed);
		}
	}

	private List<Path> tree() throws Exception {
		try (Stream<Path> entries = Files.walk(workspace)) {
			return entries.sorted().toList();
		}
	}
}
