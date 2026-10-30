package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
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
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The copy logic against an H2 source; {@code LegacyMigrationMySqlTest} repeats the essentials on real MySQL. */
class LegacyMigrationTest {

	@TempDir
	Path workspace;

	private DataSource source;
	private JdbcTemplate sourceJdbc;
	private Path media;

	@BeforeEach
	void seed() throws Exception {
		source = new DriverManagerDataSource("jdbc:h2:mem:legacy-" + UUID.randomUUID()
				+ DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_DELAY=-1", "sa", "");
		Flyway.configure().dataSource(source).locations("classpath:db/migration-h2").load().migrate();
		sourceJdbc = new JdbcTemplate(source);
		media = Files.createDirectories(workspace.resolve("old-media"));
		seedArchive(sourceJdbc, media);
	}

	@AfterEach
	void drop() {
		sourceJdbc.execute("DROP ALL OBJECTS");
	}

	static void seedArchive(JdbcTemplate jdbc, Path media) throws Exception {
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
	}

	@Test
	void copiesEveryTableWithItsIdsAndEveryFileIntoANewLibrary() throws Exception {
		Path target = workspace.resolve("new-home");
		LegacyMigration.Result result = LegacyMigration.execute(source, media, target, false);

		assertEquals(1L, result.rows().get("illustration"));
		assertEquals(1L, result.rows().get("x_like_sync_seen_token"));
		assertEquals(1, result.files());
		assertTrue(result.problems().isEmpty());
		assertEquals("image-bytes", Files.readString(target.resolve("media").resolve("2026-10").resolve("work.png")));
		assertEquals("thumb", Files.readString(target.resolve("media").resolve("thumbnails").resolve("2026-10").resolve("work.png")));

		DataSource copied = new DriverManagerDataSource("jdbc:h2:file:" + target.resolve("data").resolve("archive")
				+ DesktopServer.H2_COMPATIBILITY, "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(copied);
		assertEquals("标题", jdbc.queryForObject("SELECT title FROM illustration WHERE id = 11", String.class));
		assertEquals(LocalDateTime.parse("2026-09-01T08:30:00"),
				jdbc.queryForObject("SELECT created_at FROM illustration WHERE id = 11", LocalDateTime.class));
		assertEquals(11L, jdbc.queryForObject("SELECT imported_illustration_id FROM x_like_item WHERE x_post_id = '1001'", Long.class));
		assertEquals(LocalDateTime.parse("2026-09-03T01:02:03.456"),
				jdbc.queryForObject("SELECT discovered_at FROM x_like_item WHERE id = 31", LocalDateTime.class));
		assertEquals(4294967296L, jdbc.queryForObject("SELECT likes_order FROM x_like_item WHERE id = 31", Long.class));
		assertEquals("cursor", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class));
		assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT coverage_to_end FROM x_like_sync_state WHERE id = 2", Boolean.class));
		assertEquals(32, jdbc.queryForObject("SELECT token_sha256 FROM x_like_sync_seen_token", byte[].class).length);

		jdbc.update("INSERT INTO author (display_name) VALUES ('新作者')");
		assertEquals(6L, jdbc.queryForObject("SELECT id FROM author WHERE display_name = '新作者'", Long.class),
				"new rows continue after the copied ids");
		assertEquals(1, sourceJdbc.queryForObject("SELECT COUNT(*) FROM illustration", Integer.class), "source untouched");
		assertTrue(Files.exists(media.resolve("2026-10").resolve("work.png")), "source files untouched");
		assertNoStagingLeft();
	}

	@Test
	void checkWritesNothingButStillReportsProblems() throws Exception {
		Files.delete(media.resolve("2026-10").resolve("work.png"));
		List<Path> before = tree();

		LegacyMigration.Result result = LegacyMigration.check(source, media);

		assertEquals(1L, result.rows().get("asset"));
		assertEquals(0, result.files());
		assertEquals(List.of("Missing file: 2026-10/work.png"), result.problems());
		assertEquals(before, tree());
	}

	@Test
	void refusesALibraryThatAlreadyExistsAndLeavesItAlone() throws Exception {
		Path target = Files.createDirectories(workspace.resolve("existing"));
		Files.writeString(target.resolve("mine.txt"), "keep me");

		assertThrows(IllegalStateException.class, () -> LegacyMigration.execute(source, media, target, false));

		assertEquals("keep me", Files.readString(target.resolve("mine.txt")));
		assertEquals(1, Files.list(target).count());
		assertNoStagingLeft();
	}

	@Test
	void changedOrMissingFilesStopTheCopyAndLeaveNoTargetUnlessAccepted() throws Exception {
		Files.writeString(media.resolve("2026-10").resolve("work.png"), "tampered");
		Path target = workspace.resolve("new-home");

		IllegalStateException refused = assertThrows(IllegalStateException.class,
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

		assertEquals(List.of("Storage key leaves the media root: ../secret.txt"), result.problems());
		assertEquals(0, result.files());
	}

	@Test
	void aSchemaThatIsNotAtTheSameMigrationIsRefused() throws Exception {
		sourceJdbc.update("UPDATE flyway_schema_history SET success = FALSE WHERE version = '7'");
		Path target = workspace.resolve("new-home");

		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> LegacyMigration.execute(source, media, target, false));

		assertTrue(refused.getMessage().contains("migration 6"), refused.getMessage());
		assertFalse(Files.exists(target));
		assertNoStagingLeft();
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

	private void assertNoStagingLeft() throws Exception {
		try (Stream<Path> entries = Files.list(workspace)) {
			assertTrue(entries.noneMatch(path -> path.getFileName().toString().contains(".migrating-")));
		}
	}

	private List<Path> tree() throws Exception {
		try (Stream<Path> entries = Files.walk(workspace)) {
			return entries.sorted().toList();
		}
	}
}
