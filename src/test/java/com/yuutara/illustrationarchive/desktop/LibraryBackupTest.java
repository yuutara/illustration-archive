package com.yuutara.illustrationarchive.desktop;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup format and the restore switch on real files and a file-based H2, without Spring: what goes into a
 * backup, what a hostile or damaged ZIP gets refused for, and how an interrupted switch is undone at the next start.
 */
class LibraryBackupTest {

	private static final LibraryBackup.Progress QUIET = (stage, fraction) -> { };
	private static final String SECRET = "secret-token-value";

	@TempDir
	Path workspace;

	private Path home;
	private Path outside;

	@BeforeEach
	void library() throws Exception {
		home = Files.createDirectories(workspace.resolve("home"));
		outside = Files.createDirectories(workspace.resolve("backups"));
		LegacyMigrationTest.seedArchive(jdbc(home, null), Files.createDirectories(home.resolve("media")));
		// Everything a home holds besides the library, none of which belongs in a backup.
		write(home.resolve("config").resolve("x-oauth-tokens.properties"), "access=" + SECRET);
		write(home.resolve("config").resolve("gemini-key.txt"), SECRET);
		write(home.resolve("logs").resolve("desktop.log"), "log " + SECRET);
		write(home.resolve("webview2").resolve("Cookies"), SECRET);
		write(home.resolve("window").resolve("entry.txt"), "http://127.0.0.1:1/desktop/session?token=" + SECRET);
		write(home.resolve("media").resolve("orphan.tmp"), "not referenced");
	}

	@Test
	void backupHoldsTheLibraryAndNothingElse() throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.Manifest manifest = LibraryBackup.create(home, backup, "0.6.0", QUIET);

		assertEquals(Set.of("manifest.json", "data/archive.mv.db", "media/2026-10/work.png", "media/thumbnails/2026-10/work.png"),
				names(backup));
		String bytes = new String(Files.readAllBytes(backup), StandardCharsets.ISO_8859_1);
		assertFalse(bytes.contains(SECRET), "no credential, log or browser profile may reach a backup");
		String listing = manifestText(backup);
		assertFalse(listing.contains(workspace.getFileName().toString()), "the manifest names no machine path");
		assertFalse(listing.contains(home.toString().replace("\\", "\\\\")), "the manifest names no machine path");
		assertEquals(new LibraryBackup.Summary(1, 1, 1, 1, 1, 3, manifest.summary().bytes()), manifest.summary());
		assertEquals("0.6.0", manifest.appVersion());
		assertEquals("8", manifest.migrations().get(manifest.migrations().size() - 1).version());
		try (Stream<Path> left = Files.list(outside)) {
			assertEquals(List.of(backup), left.toList(), "no temporary file is left next to the backup");
		}
	}

	@Test
	void refusesToWriteIntoTheLibraryOrOverAFile() throws Exception {
		MaintenanceException inside = assertThrows(MaintenanceException.class,
				() -> LibraryBackup.create(home, home.resolve("logs").resolve("b.zip"), "1", QUIET));
		assertTrue(inside.getMessage().contains("数据文件夹"), inside.getMessage());

		Path junction = workspace.resolve("looks-outside");
		Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), home.toString())
				.redirectErrorStream(true).start();
		assertEquals(0, mklink.waitFor(), new String(mklink.getInputStream().readAllBytes()));
		try {
			assertThrows(MaintenanceException.class, () -> LibraryBackup.create(home, junction.resolve("b.zip"), "1", QUIET),
					"a junction into the library is still inside it");
		} finally {
			// rmdir removes the junction itself; @TempDir's clean-up cannot.
			new ProcessBuilder("cmd", "/c", "rmdir", junction.toString()).start().waitFor();
		}

		Path taken = write(outside.resolve("taken.zip"), "keep me");
		assertThrows(MaintenanceException.class, () -> LibraryBackup.create(home, taken, "1", QUIET));
		assertEquals("keep me", Files.readString(taken));
		assertEquals(outside.resolve("named.zip"), LibraryBackup.checkDestination(home, outside.resolve("named")));
	}

	@Test
	void aMissingOrChangedOriginalStopsTheBackupAndLeavesNothing() throws Exception {
		Path original = home.resolve("media").resolve("2026-10").resolve("work.png");
		Files.writeString(original, "changed since import");
		MaintenanceException changed = assertThrows(MaintenanceException.class,
				() -> LibraryBackup.create(home, outside.resolve("b.zip"), "1", QUIET));
		assertTrue(changed.getMessage().contains("不一致"), changed.getMessage());

		Files.delete(original);
		MaintenanceException missing = assertThrows(MaintenanceException.class,
				() -> LibraryBackup.create(home, outside.resolve("b.zip"), "1", QUIET));
		assertTrue(missing.getMessage().contains("2026-10/work.png"), missing.getMessage());
		try (Stream<Path> left = Files.list(outside)) {
			assertEquals(0, left.count());
		}
	}

	@Test
	void anOpenDatabaseIsNotBackedUp() throws Exception {
		try (Connection held = LibraryBackup.open(home.resolve("data"))) {
			MaintenanceException busy = assertThrows(MaintenanceException.class,
					() -> LibraryBackup.create(home, outside.resolve("b.zip"), "1", QUIET));
			assertTrue(busy.getMessage().contains("使用中"), busy.getMessage());
		}
	}

	@Test
	void cancellingLeavesNoBackup() {
		LibraryBackup.Progress cancelled = new LibraryBackup.Progress() {
			@Override
			public void update(String stage, double fraction) {
			}

			@Override
			public boolean cancelled() {
				return true;
			}
		};
		assertTrue(assertThrows(MaintenanceException.class,
				() -> LibraryBackup.create(home, outside.resolve("b.zip"), "1", cancelled)).isCancelled());
		assertFalse(Files.exists(outside.resolve("b.zip")));
	}

	@Test
	void restoreBringsBackTheBackedUpLibraryAndKeepsTheReplacedOne() throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		byte[] original = Files.readAllBytes(home.resolve("media").resolve("2026-10").resolve("work.png"));
		byte[] config = Files.readAllBytes(home.resolve("config").resolve("x-oauth-tokens.properties"));
		// Changes after the backup, which the restore takes away.
		JdbcTemplate changed = jdbc(home, null);
		changed.update("DELETE FROM illustration WHERE id = 11");
		changed.update("INSERT INTO tag (name) VALUES ('后来加的')");
		Files.delete(home.resolve("media").resolve("2026-10").resolve("work.png"));
		write(home.resolve("media").resolve("2026-11").resolve("new.png"), "new");

		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);
		assertEquals("8", candidate.schemaBefore());
		assertEquals("8", candidate.schemaAfter());
		assertEquals(1, candidate.manifest().summary().works());
		assertEquals(0, jdbc(home, null).queryForObject("SELECT COUNT(*) FROM illustration", Integer.class),
				"checking a backup leaves the library as it is");

		Path previous = LibraryRestore.switchTo(home, candidate.id());
		LibraryRestore.confirm(home, candidate.id());

		JdbcTemplate restored = jdbc(home, null);
		assertEquals("标题", restored.queryForObject("SELECT title FROM illustration WHERE id = 11", String.class));
		assertEquals(21, restored.queryForObject("SELECT id FROM asset WHERE illustration_id = 11", Long.class));
		assertEquals(9, restored.queryForObject("SELECT tag_id FROM illustration_tag WHERE illustration_id = 11", Long.class));
		assertEquals(0, restored.queryForObject("SELECT COUNT(*) FROM tag WHERE name = '后来加的'", Integer.class));
		assertEquals("42", restored.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account", String.class),
				"the account the sync belongs to comes back with the library");
		assertEquals(11, restored.queryForObject("SELECT imported_illustration_id FROM x_like_item WHERE id = 31", Long.class));
		assertArrayEquals(original, Files.readAllBytes(home.resolve("media").resolve("2026-10").resolve("work.png")));
		assertFalse(Files.exists(home.resolve("media").resolve("2026-11")));
		assertArrayEquals(config, Files.readAllBytes(home.resolve("config").resolve("x-oauth-tokens.properties")),
				"settings and credentials stay as they were");
		assertTrue(Files.exists(previous.resolve("media").resolve("2026-11").resolve("new.png")), "the replaced library is kept");
		assertEquals(1, jdbc(previous.getParent(), "previous").queryForObject(
				"SELECT COUNT(*) FROM tag WHERE name = '后来加的'", Integer.class));
		assertEquals("DONE", journal(candidate.id()).getProperty("state"));
		assertNull(LibraryRestore.recover(home), "a finished restore is left alone at the next start");
		assertTrue(Files.exists(home.resolve("data").resolve("archive.mv.db")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"traversal", "absolute", "stream", "drive", "device", "trailingDot", "caseTwin", "unlisted",
			"missingEntry", "longer", "shorter", "changed", "noManifest", "newerFormat", "brokenManifest", "noDatabase",
			"notAZip", "hugeSize"})
	void aHostileOrDamagedBackupIsRefusedAndTheLibraryStaysAsItWas(String damage) throws Exception {
		Path good = outside.resolve("good.zip");
		LibraryBackup.create(home, good, "1", QUIET);
		Map<String, byte[]> entries = entries(good);
		LibraryBackup.Manifest manifest = JsonMapper.builder().build().readValue(entries.remove("manifest.json"),
				LibraryBackup.Manifest.class);
		List<LibraryBackup.Entry> listed = new ArrayList<>(manifest.entries());
		Consumer<String> extra = name -> {
			entries.put(name, "x".getBytes(StandardCharsets.UTF_8));
			listed.add(new LibraryBackup.Entry(name, 1, sha256("x".getBytes(StandardCharsets.UTF_8))));
		};
		int format = manifest.format();
		boolean withManifest = true;
		String rawManifest = null;
		switch (damage) {
			case "traversal" -> extra.accept("media/../escaped.png");
			case "absolute" -> extra.accept("/media/a.png");
			case "stream" -> extra.accept("media/a.png:hidden");
			case "drive" -> extra.accept("media/C:/a.png");
			case "device" -> extra.accept("media/CON.png");
			case "trailingDot" -> extra.accept("media/a.png.");
			case "caseTwin" -> {
				extra.accept("media/a.png");
				extra.accept("media/A.png");
			}
			case "unlisted" -> entries.put("media/unlisted.png", new byte[] {1});
			case "missingEntry" -> entries.remove("media/2026-10/work.png");
			case "longer" -> entries.put("media/2026-10/work.png", "image-bytes and more".getBytes(StandardCharsets.UTF_8));
			case "shorter" -> entries.put("media/2026-10/work.png", "image".getBytes(StandardCharsets.UTF_8));
			case "changed" -> entries.put("media/2026-10/work.png", "IMAGE-BYTES".getBytes(StandardCharsets.UTF_8));
			case "noManifest" -> withManifest = false;
			case "newerFormat" -> format = LibraryBackup.FORMAT + 1;
			case "brokenManifest" -> rawManifest = "{\"format\": 1, \"entries\": [";
			case "noDatabase" -> {
				entries.remove("data/archive.mv.db");
				listed.removeIf(entry -> entry.path().equals("data/archive.mv.db"));
			}
			case "notAZip" -> { }
			case "hugeSize" -> {
				// The whole stays a valid long, but adding the free-space margin to it would wrap around.
				long others = listed.stream().filter(entry -> !entry.path().equals("media/2026-10/work.png"))
						.mapToLong(LibraryBackup.Entry::size).sum();
				listed.replaceAll(entry -> entry.path().equals("media/2026-10/work.png") ? new LibraryBackup.Entry(entry.path(),
						Long.MAX_VALUE - LibraryBackup.SPACE_MARGIN + 1 - others, entry.sha256()) : entry);
			}
			default -> throw new IllegalArgumentException(damage);
		}
		Path hostile = outside.resolve("hostile.zip");
		if (damage.equals("notAZip")) {
			Files.writeString(hostile, "PK not really");
		} else {
			LibraryBackup.Manifest tampered = new LibraryBackup.Manifest(format, manifest.createdAt(), manifest.appVersion(),
					manifest.h2Version(), manifest.migrations(), manifest.summary(), listed);
			byte[] json = rawManifest != null ? rawManifest.getBytes(StandardCharsets.UTF_8)
					: JsonMapper.builder().build().writeValueAsBytes(tampered);
			zip(hostile, entries, withManifest ? json : null);
		}
		byte[] database = Files.readAllBytes(home.resolve("data").resolve("archive.mv.db"));

		MaintenanceException refused = assertThrows(MaintenanceException.class, () -> LibraryRestore.prepare(home, hostile, QUIET));

		assertNotNull(refused.getMessage());
		assertArrayEquals(database, Files.readAllBytes(home.resolve("data").resolve("archive.mv.db")));
		assertFalse(Files.exists(workspace.resolve("escaped.png")) || Files.exists(home.resolve("escaped.png")));
		try (Stream<Path> operations = Files.list(home.resolve(LibraryRestore.RECOVERY))) {
			assertEquals(0, operations.count(), "a refused backup leaves no unpacked copy behind");
		}
	}

	@Test
	void aBackupFromANewerSchemaIsRefused() throws Exception {
		jdbc(home, null).update("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,"
				+ " checksum, installed_by, execution_time, success) VALUES (99, '99', 'future', 'SQL', 'V99__future.sql', 1, 'sa', 0, TRUE)");
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "9.9", QUIET);
		MaintenanceException refused = assertThrows(MaintenanceException.class, () -> LibraryRestore.prepare(home, backup, QUIET));
		assertTrue(refused.getMessage().contains("更新版本"), refused.getMessage());
	}

	@Test
	void aBackupWhoseMigrationsDifferFromThisAppIsRefused() throws Exception {
		jdbc(home, null).update("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '8'");
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		MaintenanceException refused = assertThrows(MaintenanceException.class, () -> LibraryRestore.prepare(home, backup, QUIET));
		assertTrue(refused.getMessage().contains("不一致"), refused.getMessage());
	}

	@Test
	void anOlderBackupIsUpgradedInTheUnpackedCopyOnly() throws Exception {
		Path old = Files.createDirectories(workspace.resolve("old-home"));
		String url = "jdbc:h2:file:" + old.resolve("data").resolve("archive") + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_ON_EXIT=FALSE";
		Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration-h2").target("7").load().migrate();
		JdbcTemplate seven = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
		seven.update("INSERT INTO illustration (id, title) VALUES (1, '旧')");
		Path backup = outside.resolve("old.zip");
		LibraryBackup.create(old, backup, "0.5.0", QUIET);
		byte[] zip = Files.readAllBytes(backup);

		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);

		assertEquals("7", candidate.schemaBefore());
		assertEquals("8", candidate.schemaAfter());
		assertArrayEquals(zip, Files.readAllBytes(backup), "the backup itself is only read");
		LibraryRestore.switchTo(home, candidate.id());
		assertEquals(8, jdbc(home, null).queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL", Integer.class));
		assertEquals("旧", jdbc(home, null).queryForObject("SELECT title FROM illustration WHERE id = 1", String.class));
	}

	/**
	 * A crash can stop the switch after any rename, before or after the journal recorded it. The next start puts the
	 * library that was there back, from the journal and the folders it finds.
	 */
	@ParameterizedTest
	@CsvSource({"0,false", "0,true", "1,false", "1,true", "2,false", "2,true", "3,false", "3,true", "4,false"})
	void aSwitchInterruptedAtAnyStepIsUndoneAtTheNextStart(int recorded, boolean nextMoveHappened) throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		jdbc(home, null).update("INSERT INTO tag (name) VALUES ('恢复前才有')");
		byte[] database = Files.readAllBytes(home.resolve("data").resolve("archive.mv.db"));
		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);
		Path operation = home.resolve(LibraryRestore.RECOVERY).resolve(candidate.id());
		List<Path[]> moves = List.of(
				new Path[] {home.resolve("data"), operation.resolve("previous").resolve("data")},
				new Path[] {home.resolve("media"), operation.resolve("previous").resolve("media")},
				new Path[] {operation.resolve("incoming").resolve("data"), home.resolve("data")},
				new Path[] {operation.resolve("incoming").resolve("media"), home.resolve("media")});
		int made = recorded + (nextMoveHappened ? 1 : 0);
		for (int step = 0; step < made; step++) {
			Files.move(moves.get(step)[0], moves.get(step)[1]);
		}
		Properties journal = journal(candidate.id());
		journal.setProperty("state", recorded == 4 ? "SWITCHED" : "SWITCHING");
		journal.setProperty("done", Integer.toString(recorded));
		for (int step = 0; step < 4; step++) {
			journal.setProperty("present." + step, "true");
		}
		saveJournal(candidate.id(), journal);

		String note = LibraryRestore.recover(home);

		assertNotNull(note);
		assertArrayEquals(database, Files.readAllBytes(home.resolve("data").resolve("archive.mv.db")));
		assertEquals(1, jdbc(home, null).queryForObject("SELECT COUNT(*) FROM tag WHERE name = '恢复前才有'", Integer.class));
		assertTrue(Files.exists(home.resolve("media").resolve("2026-10").resolve("work.png")));
		assertEquals("ROLLED_BACK", journal(candidate.id()).getProperty("state"));
		assertFalse(Files.exists(operation.resolve("incoming")), "only the unpacked copy of the backup is dropped");
		assertNull(LibraryRestore.recover(home), "an undone restore stays undone");
	}

	@Test
	void whenTheJournalAndTheFoldersDisagreeNothingIsMoved() throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);
		Path operation = home.resolve(LibraryRestore.RECOVERY).resolve(candidate.id());
		// The first rename is recorded as in progress, yet both its source and its target are there.
		Files.createDirectories(operation.resolve("previous").resolve("data"));
		Properties journal = journal(candidate.id());
		journal.setProperty("state", "SWITCHING");
		journal.setProperty("done", "0");
		saveJournal(candidate.id(), journal);

		MaintenanceException stuck = assertThrows(MaintenanceException.class, () -> LibraryRestore.recover(home));

		assertTrue(stuck.getMessage().contains("对不上"), stuck.getMessage());
		assertTrue(Files.exists(home.resolve("data").resolve("archive.mv.db")));
		assertTrue(Files.exists(operation.resolve("incoming").resolve("data").resolve("archive.mv.db")));
		assertEquals("SWITCHING", journal(candidate.id()).getProperty("state"));
	}

	@Test
	void aRenameThatFailsIsUndoneOnTheSpot() throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		jdbc(home, null).update("INSERT INTO tag (name) VALUES ('恢复前才有')");
		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);
		// Something already sits where the media folder has to go, so the second rename fails.
		write(home.resolve(LibraryRestore.RECOVERY).resolve(candidate.id()).resolve("previous").resolve("media"), "in the way");

		MaintenanceException failed = assertThrows(MaintenanceException.class, () -> LibraryRestore.switchTo(home, candidate.id()));

		assertTrue(failed.getMessage().contains("已退回"), failed.getMessage());
		assertEquals(1, jdbc(home, null).queryForObject("SELECT COUNT(*) FROM tag WHERE name = '恢复前才有'", Integer.class));
		assertTrue(Files.exists(home.resolve("media").resolve("2026-10").resolve("work.png")));
		assertEquals("ROLLED_BACK", journal(candidate.id()).getProperty("state"));
	}

	@Test
	void anUndoThatAlsoFailsIsReportedStuckAndTheNextStartFinishesIt() throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		jdbc(home, null).update("INSERT INTO tag (name) VALUES ('恢复前才有')");
		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);
		int[] renames = {0};
		LibraryRestore.Mover failing = (from, to) -> {
			if (++renames[0] >= 2) {
				throw new java.nio.file.AccessDeniedException(from.toString());
			}
			Files.move(from, to);
		};

		MaintenanceException stuck = assertThrows(MaintenanceException.class,
				() -> LibraryRestore.switchTo(home, candidate.id(), failing));

		assertTrue(stuck.isStuck(), stuck.getMessage());
		assertEquals("ROLLING_BACK", journal(candidate.id()).getProperty("state"));
		assertFalse(Files.exists(home.resolve("data")), "the library is still in previous, nothing put in its place");
		assertNotNull(LibraryRestore.recover(home));
		assertEquals(1, jdbc(home, null).queryForObject("SELECT COUNT(*) FROM tag WHERE name = '恢复前才有'", Integer.class));
		assertEquals("ROLLED_BACK", journal(candidate.id()).getProperty("state"));
	}

	@Test
	void aDeclaredSizeNearTheLimitCannotWrapPastTheSpaceCheck() throws Exception {
		for (long declared : new long[] {Long.MAX_VALUE - LibraryBackup.SPACE_MARGIN + 1, Long.MAX_VALUE, -1}) {
			MaintenanceException refused = assertThrows(MaintenanceException.class,
					() -> LibraryBackup.requireSpace(outside, declared), Long.toString(declared));
			assertTrue(refused.getMessage().contains("磁盘空间不够"), refused.getMessage());
		}
		LibraryBackup.requireSpace(outside, 1);
	}

	@Test
	void aCheckedBackupThatWasNeverUsedIsDroppedAtTheNextStart() throws Exception {
		Path backup = outside.resolve("b.zip");
		LibraryBackup.create(home, backup, "1", QUIET);
		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backup, QUIET);

		assertNull(LibraryRestore.recover(home));

		assertFalse(Files.exists(home.resolve(LibraryRestore.RECOVERY).resolve(candidate.id())));
		assertEquals(1, jdbc(home, null).queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertThrows(IllegalArgumentException.class, () -> LibraryRestore.operation(home, "..\\..\\x"));
	}

	private static JdbcTemplate jdbc(Path base, String library) {
		Path data = (library == null ? base : base.resolve(library)).resolve("data");
		String url = "jdbc:h2:file:" + data.resolve("archive") + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_ON_EXIT=FALSE";
		DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
		if (!Files.exists(data.resolve("archive.mv.db"))) {
			Flyway.configure().dataSource(source).locations("classpath:db/migration-h2").load().migrate();
		}
		return new JdbcTemplate(source);
	}

	private Properties journal(String id) throws IOException {
		Properties journal = new Properties();
		try (var input = Files.newInputStream(home.resolve(LibraryRestore.RECOVERY).resolve(id).resolve("journal.properties"))) {
			journal.load(input);
		}
		return journal;
	}

	private void saveJournal(String id, Properties journal) throws IOException {
		try (OutputStream output = Files.newOutputStream(home.resolve(LibraryRestore.RECOVERY).resolve(id).resolve("journal.properties"))) {
			journal.store(output, null);
		}
	}

	private static Path write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		return Files.writeString(file, text);
	}

	private static Set<String> names(Path zip) throws IOException {
		try (ZipFile file = new ZipFile(zip.toFile())) {
			return file.stream().map(ZipEntry::getName).collect(Collectors.toSet());
		}
	}

	private static String manifestText(Path zip) throws IOException {
		try (ZipFile file = new ZipFile(zip.toFile())) {
			return new String(file.getInputStream(file.getEntry("manifest.json")).readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Map<String, byte[]> entries(Path zip) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipFile file = new ZipFile(zip.toFile())) {
			for (ZipEntry entry : file.stream().toList()) {
				entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
			}
		}
		return entries;
	}

	private static void zip(Path target, Map<String, byte[]> entries, byte[] manifest) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
			if (manifest != null) {
				zip.putNextEntry(new ZipEntry("manifest.json"));
				zip.write(manifest);
				zip.closeEntry();
			}
		}
	}

	private static String sha256(byte[] content) {
		return HexFormat.of().formatHex(LibraryBackup.sha256().digest(content));
	}
}
