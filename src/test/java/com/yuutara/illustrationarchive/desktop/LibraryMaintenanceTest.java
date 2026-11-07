package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backup and restore against the real desktop server (Spring, Tomcat on 127.0.0.1, file-based H2, local media), with
 * the launcher's parts that need a screen replaced: file dialogs answer with a fixed path, and the native window is a
 * queue of what it would show. Starting and stopping go through the same {@link DesktopServer} calls as the launcher,
 * on one token, so the page's session survives every restart as it does in the app.
 */
class LibraryMaintenanceTest {

	@TempDir
	Path workspace;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final ObjectMapper json = new ObjectMapper();
	private final BlockingQueue<LibraryMaintenance.Outcome> outcomes = new LinkedBlockingQueue<>();
	private final List<String> stages = new ArrayList<>();
	private Path home;
	private Path backups;
	private TestServer server;
	private Path saveTo;
	private Path openFrom;
	private LibraryMaintenance maintenance;
	private String cookie;
	/** The switch's renames; a test can make one of them fail. */
	private volatile LibraryRestore.Mover mover = Files::move;
	/** What happens elsewhere while the save dialog is open. */
	private volatile Runnable whileSaving = () -> { };
	/** The old app's config the open dialog answers with, and the media folder asked for when it is relative. */
	private Path legacyConfig;
	private Path legacyMedia;
	private final List<String> mediaAskedFor = new ArrayList<>();
	/** How the old app's config is read; tests replace MySQL with an H2 source. */
	private volatile LibraryMaintenance.LegacySources sources = LegacyMigration.Source::read;

	@BeforeEach
	void start() throws Exception {
		home = Files.createDirectories(workspace.resolve("home"));
		backups = Files.createDirectories(workspace.resolve("backups"));
		server = new TestServer();
		maintenance = new LibraryMaintenance(home, server, new LibraryMaintenance.Dialogs() {
			@Override
			public Optional<Path> saveBackup(String suggestedName) {
				whileSaving.run();
				return Optional.ofNullable(saveTo);
			}

			@Override
			public Optional<Path> openBackup() {
				return Optional.ofNullable(openFrom);
			}

			@Override
			public Optional<Path> openLegacyConfig() {
				return Optional.ofNullable(legacyConfig);
			}

			@Override
			public Optional<Path> chooseLegacyMedia(String configured) {
				mediaAskedFor.add(configured);
				return Optional.ofNullable(legacyMedia);
			}
		}, new LibraryMaintenance.Screen() {
			@Override
			public synchronized void stage(LibraryMaintenance.Kind kind, String text, double fraction, boolean cancellable) {
				stages.add(text);
			}

			@Override
			public void finished(LibraryMaintenance.Outcome outcome) {
				outcomes.add(outcome);
			}
		}, "test", (from, to) -> mover.move(from, to), config -> sources.read(config));
		server.start(false);
		cookie = openSession();
	}

	@AfterEach
	void stop() {
		if (server.running()) {
			server.stop();
		}
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void backupAndRestoreFromSettingsBringBackTheLibraryAndKeepSettings() throws Exception {
		importPng("red.png", Color.RED);
		importPng("blue.png", Color.BLUE);
		assertEquals(201, send("POST", "/api/tags", "{\"name\":\"备份前\"}").statusCode());
		assertEquals(204, send("PUT", "/api/desktop/settings/proxy", "{\"address\":\"127.0.0.1:7890\"}").statusCode());
		saveTo = backups.resolve("library");

		HttpResponse<String> started = send("POST", "/api/desktop/backup", null);
		assertEquals(200, started.statusCode(), started.body());
		LibraryMaintenance.Outcome backedUp = outcome();

		assertTrue(backedUp.succeeded(), backedUp.message());
		assertTrue(backedUp.libraryOpen());
		Path backup = backups.resolve("library.zip");
		assertEquals(backup, backedUp.location());
		assertTrue(Files.isRegularFile(backup));
		assertEquals(200, send("GET", "/api/desktop/restore", null).statusCode(), "the same session works after the restart");

		// After the backup: a work less, a tag more, and a changed setting that the restore must not undo.
		long red = jdbc().queryForObject("SELECT illustration_id FROM asset WHERE original_filename = 'red.png'", Long.class);
		HttpResponse<String> deleted = send("DELETE", "/api/illustrations/" + red, null);
		assertTrue(deleted.statusCode() < 300, deleted.body());
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertEquals(201, send("POST", "/api/tags", "{\"name\":\"备份后\"}").statusCode());
		assertEquals(204, send("PUT", "/api/desktop/settings/proxy", "{\"address\":\"127.0.0.1:7891\"}").statusCode());
		Map<Path, byte[]> config = files(home.resolve("config"));

		openFrom = backup;
		JsonNode checking = body(send("POST", "/api/desktop/restore/check", null));
		assertTrue(List.of("CHECKING", "READY").contains(checking.get("phase").asString()), checking.toString());
		JsonNode ready = awaitPhase("READY");
		assertEquals(2, ready.get("preview").get("summary").get("works").asLong());
		assertEquals("library.zip", ready.get("preview").get("fileName").asString());
		assertEquals(409, send("POST", "/api/desktop/backup", null).statusCode(), "one operation at a time");
		assertEquals(409, send("POST", "/api/desktop/restore/apply", "{\"id\":\"20000101-000000-0000\"}").statusCode(),
				"only the backup that was checked can be restored");

		String id = ready.get("id").asString();
		assertEquals(202, send("POST", "/api/desktop/restore/apply", "{\"id\":\"" + id + "\"}").statusCode());
		LibraryMaintenance.Outcome restored = outcome();

		assertTrue(restored.succeeded(), restored.message());
		assertTrue(restored.libraryOpen());
		assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM tag WHERE name = '备份前'", Integer.class));
		assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM tag WHERE name = '备份后'", Integer.class));
		HttpResponse<String> image = send("GET", "/api/assets/" + jdbc().queryForObject(
				"SELECT id FROM asset WHERE original_filename = 'red.png'", Long.class) + "/content", null);
		assertEquals(200, image.statusCode(), "restored originals are served again");
		Map<Path, byte[]> after = files(home.resolve("config"));
		assertEquals(config.keySet(), after.keySet());
		config.forEach((file, bytes) -> assertArrayEquals(bytes, after.get(file), file + " must not change"));
		assertTrue(body(send("GET", "/api/desktop/settings", null)).get("proxy").asString().endsWith("7891"));
		assertEquals(home.resolve("recovery").resolve(id).resolve("previous"), restored.location());
		assertEquals(1, new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
				LibraryBackup.url(restored.location().resolve("data")), "sa", ""))
				.queryForObject("SELECT COUNT(*) FROM tag WHERE name = '备份后'", Integer.class), "the replaced library is kept as it was");
		assertTrue(stages.contains("正在等待进行中的操作结束（例如同步或导入）…"));
		assertEquals("IDLE", body(send("GET", "/api/desktop/restore", null)).get("phase").asString());
	}

	@Test
	void aLibraryThatDoesNotOpenIsRestoredFromABackup() throws Exception {
		importPng("red.png", Color.RED);
		saveTo = backups.resolve("before-damage.zip");
		assertTrue(maintenance.backup());
		assertTrue(outcome().succeeded());
		server.stop();
		Path database = home.resolve("data").resolve("archive.mv.db");
		byte[] damaged = "this is not an H2 file".repeat(200).getBytes(StandardCharsets.UTF_8);
		Files.write(database, damaged);
		assertThrows(RuntimeException.class, () -> server.start(false), "a damaged library must really fail to open");
		assertFalse(server.running());

		maintenance.check(saveTo);
		String id = awaitReady();
		maintenance.restore(id);
		LibraryMaintenance.Outcome restored = outcome();

		assertTrue(restored.succeeded(), restored.message());
		assertTrue(server.running());
		cookie = openSession();
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertArrayEquals(damaged, Files.readAllBytes(restored.location().resolve("data").resolve("archive.mv.db")),
				"the library that did not open is kept untouched");
	}

	@Test
	void aRestoredLibraryThatDoesNotOpenIsReplacedByThePreviousOneAgain() throws Exception {
		importPng("red.png", Color.RED);
		saveTo = backups.resolve("b.zip");
		assertTrue(maintenance.backup());
		assertTrue(outcome().succeeded());
		assertEquals(201, send("POST", "/api/tags", "{\"name\":\"恢复前才有\"}").statusCode());
		maintenance.check(saveTo);
		String id = awaitReady();
		server.failNextStarts.set(1);

		maintenance.restore(id);
		LibraryMaintenance.Outcome outcome = outcome();

		assertFalse(outcome.succeeded());
		assertTrue(outcome.libraryOpen(), outcome.message());
		assertTrue(outcome.message().contains("已退回恢复前的图库"), outcome.message());
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM tag WHERE name = '恢复前才有'", Integer.class));
		assertFalse(Files.exists(home.resolve("recovery").resolve(id).resolve("incoming")));
		assertEquals("IDLE", maintenance.restoreView().phase().name());
		assertEquals(200, send("GET", "/api/desktop/restore", null).statusCode());
	}

	@Test
	void aBackupThatFailsStillReopensTheLibrary() throws Exception {
		importPng("red.png", Color.RED);
		String key = jdbc().queryForObject("SELECT storage_key FROM asset", String.class);
		Files.delete(home.resolve("media").resolve(key));
		saveTo = backups.resolve("b.zip");

		assertTrue(maintenance.backup());
		LibraryMaintenance.Outcome outcome = outcome();

		assertFalse(outcome.succeeded());
		assertTrue(outcome.message().contains("原图"), outcome.message());
		assertTrue(outcome.libraryOpen());
		assertEquals(200, send("GET", "/api/desktop/restore", null).statusCode());
		try (Stream<Path> left = Files.list(backups)) {
			assertEquals(0, left.count(), "a failed backup leaves no file behind");
		}
	}

	@Test
	void requestsAreRefusedWhileTheGateIsClosedAndTheLibraryIsUntouchedWhenTheyDoNotFinish() throws Exception {
		MaintenanceGate gate = server.gate();
		assertTrue(gate.close(Duration.ofSeconds(1), () -> false));
		HttpResponse<String> refused = send("GET", "/api/desktop/restore", null);
		assertEquals(503, refused.statusCode());
		assertTrue(refused.body().contains("正在备份或恢复"), refused.body());
		gate.open();
		assertEquals(200, send("GET", "/api/desktop/restore", null).statusCode());
	}

	@Test
	void aBackupWaitsWhileAnXLoginIsInProgress() throws Exception {
		HttpResponse<String> login = send("POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}");
		assertEquals(200, login.statusCode(), login.body());
		assertEquals("WAITING", body(login).get("status").get("state").asString());

		HttpResponse<String> refused = send("POST", "/api/desktop/backup", null);

		assertEquals(409, refused.statusCode());
		assertTrue(refused.body().contains("X 授权正在进行"), refused.body());
		assertTrue(server.running());
	}

	@Test
	void aSwitchThatCannotBeUndoneOpensNothingAndTheNextStartPutsTheLibraryBack() throws Exception {
		importPng("red.png", Color.RED);
		saveTo = backups.resolve("b.zip");
		assertTrue(maintenance.backup());
		assertTrue(outcome().succeeded());
		maintenance.check(saveTo);
		String id = awaitReady();
		// The first rename (data out) works; the second (media out) and the undo of the first both fail.
		AtomicInteger renames = new AtomicInteger();
		mover = (from, to) -> {
			if (renames.incrementAndGet() >= 2) {
				throw new java.nio.file.AccessDeniedException(from.toString());
			}
			Files.move(from, to);
		};

		maintenance.restore(id);
		LibraryMaintenance.Outcome outcome = outcome();

		assertTrue(outcome.stuck(), outcome.message());
		assertFalse(outcome.libraryOpen());
		assertFalse(server.running(), "nothing may open a library that is not whole");
		assertFalse(Files.exists(home.resolve("data").resolve("archive.mv.db")), "no empty library takes its place");
		Path previous = home.resolve("recovery").resolve(id).resolve("previous");
		assertTrue(Files.exists(previous.resolve("data").resolve("archive.mv.db")));
		assertEquals(3, renames.get());

		mover = Files::move;
		assertNotNull(LibraryRestore.recover(home), "the next start finishes the undo before opening anything");
		server.start(false);
		cookie = openSession();
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
	}

	@Test
	void aRestoredLibraryAnswersNoRequestUntilItIsConfirmed() throws Exception {
		importPng("red.png", Color.RED);
		importPng("blue.png", Color.BLUE);
		saveTo = backups.resolve("b.zip");
		assertTrue(maintenance.backup());
		assertTrue(outcome().succeeded());
		long red = jdbc().queryForObject("SELECT illustration_id FROM asset WHERE original_filename = 'red.png'", Long.class);
		maintenance.check(saveTo);
		String id = awaitReady();
		List<Integer> whileHeld = java.util.Collections.synchronizedList(new ArrayList<>());
		server.afterStart = held -> {
			if (held) {
				whileHeld.add(deleteStatus(red));
			}
		};

		maintenance.restore(id);
		LibraryMaintenance.Outcome restored = outcome();

		assertTrue(restored.succeeded(), restored.message());
		assertEquals(List.of(503), whileHeld, "an old tab cannot write before the restore is confirmed");
		assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertTrue(deleteStatus(red) < 300, "once confirmed, the library is open again");
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
	}

	@Test
	void aRestoredLibraryThatDoesNotCheckOutIsRolledBackWithoutHavingServedARequest() throws Exception {
		importPng("red.png", Color.RED);
		importPng("blue.png", Color.BLUE);
		saveTo = backups.resolve("b.zip");
		assertTrue(maintenance.backup());
		assertTrue(outcome().succeeded());
		assertEquals(201, send("POST", "/api/tags", "{\"name\":\"恢复前才有\"}").statusCode());
		long red = jdbc().queryForObject("SELECT illustration_id FROM asset WHERE original_filename = 'red.png'", Long.class);
		maintenance.check(saveTo);
		String id = awaitReady();
		List<Integer> whileHeld = java.util.Collections.synchronizedList(new ArrayList<>());
		server.afterStart = held -> {
			whileHeld.add(held ? deleteStatus(red) : 0);
			server.countOffset = held ? 1 : 0;
		};

		maintenance.restore(id);
		LibraryMaintenance.Outcome outcome = outcome();

		assertFalse(outcome.succeeded());
		assertTrue(outcome.libraryOpen(), outcome.message());
		assertEquals(List.of(503, 0), whileHeld, "the restored library refused the delete, then the old one reopened");
		assertEquals(2, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM tag WHERE name = '恢复前才有'", Integer.class));
	}

	@Test
	void aLoginStartedWhileTheSaveDialogIsOpenStopsTheBackupAndKeepsTheLogin() throws Exception {
		importPng("red.png", Color.RED);
		saveTo = backups.resolve("b.zip");
		whileSaving = () -> startLogin();

		assertTrue(maintenance.backup());
		LibraryMaintenance.Outcome outcome = outcome();

		assertFalse(outcome.succeeded());
		assertTrue(outcome.message().contains("X 授权正在进行"), outcome.message());
		assertTrue(outcome.libraryOpen());
		assertEquals("WAITING", body(send("GET", "/api/x-auth/status", null)).get("status").get("state").asString(),
				"the login the user started is still waiting for X");
		try (Stream<Path> left = Files.list(backups)) {
			assertEquals(0, left.count());
		}
	}

	@Test
	void aLoginStartedBeforeARestoreIsAppliedStopsTheRestoreAndKeepsTheCheckedBackup() throws Exception {
		importPng("red.png", Color.RED);
		saveTo = backups.resolve("b.zip");
		assertTrue(maintenance.backup());
		assertTrue(outcome().succeeded());
		maintenance.check(saveTo);
		String id = awaitReady();
		startLogin();

		maintenance.restore(id);
		LibraryMaintenance.Outcome outcome = outcome();

		assertFalse(outcome.succeeded());
		assertTrue(outcome.message().contains("X 授权正在进行"), outcome.message());
		assertEquals(LibraryMaintenance.Phase.READY, maintenance.restoreView().phase(), "the checked backup can be tried again");
		assertEquals("WAITING", body(send("GET", "/api/x-auth/status", null)).get("status").get("state").asString());
	}

	/* ---------------- Import from the old app ---------------- */

	/** An old library at V7 in H2, standing in for MySQL, with its media; the config file only has to exist. */
	private JdbcTemplate oldLibrary(String storageRoot) throws Exception {
		javax.sql.DataSource old = LegacyMigrationTest.h2Source("7");
		JdbcTemplate oldJdbc = new JdbcTemplate(old);
		Path oldMedia = Files.createDirectories(workspace.resolve("old-media"));
		LegacyMigrationTest.seedArchive(oldJdbc, oldMedia, false);
		legacyConfig = Files.writeString(workspace.resolve("application-local.properties"), "replaced by the test source");
		sources = config -> new LegacyMigration.Source("mysql.example:3306", "illustration_archive",
				storageRoot == null ? oldMedia.toString() : storageRoot, old, List.of("pw-secret"));
		return oldJdbc;
	}

	@Test
	void anImportFromSettingsReplacesTheWholeLibraryWithTheOldOneUpgradedAndKeepsThePreviousOne() throws Exception {
		importPng("red.png", Color.RED);
		assertEquals(201, send("POST", "/api/tags", "{\"name\":\"导入前才有\"}").statusCode());
		assertEquals(204, send("PUT", "/api/desktop/settings/proxy", "{\"address\":\"127.0.0.1:7890\"}").statusCode());
		Map<Path, byte[]> config = files(home.resolve("config"));
		JdbcTemplate old = oldLibrary(null);
		List<String> v7Tables = LegacyMigrationTest.TABLES.subList(0, LegacyMigrationTest.TABLES.size() - 1);
		var oldRows = LegacyMigrationTest.everyRow(old, v7Tables);
		var oldHistory = old.queryForList("SELECT * FROM flyway_schema_history");
		Map<Path, byte[]> oldMedia = files(workspace.resolve("old-media"));

		JsonNode checking = body(send("POST", "/api/desktop/import/check", null));
		assertEquals("IMPORT", checking.get("kind").asString());
		JsonNode ready = awaitPhase("READY");
		assertEquals("IMPORT", ready.get("kind").asString());
		JsonNode preview = ready.get("importPreview");
		assertEquals("mysql.example:3306 / illustration_archive", preview.get("source").asString());
		assertEquals("7", preview.get("schemaBefore").asString());
		assertEquals("9", preview.get("schemaAfter").asString());
		assertEquals(1, preview.get("incoming").get("works").asLong());
		assertEquals(1, preview.get("incoming").get("inbox").asLong());
		assertEquals(1, preview.get("current").get("works").asLong(), "what would be replaced is shown beside it");
		assertEquals(1, preview.get("current").get("tags").asLong());
		assertEquals(0, preview.get("problemCount").asInt());
		String id = ready.get("id").asString();
		assertTrue(Files.isDirectory(home.resolve("recovery").resolve(id).resolve("incoming").resolve("data")),
				"the copy waits beside the library, on its disk");
		assertEquals(409, send("POST", "/api/desktop/backup", null).statusCode(), "one operation at a time");
		assertEquals(409, send("POST", "/api/desktop/restore/apply", "{\"id\":\"" + id + "\"}").statusCode(),
				"a checked import is not a backup to restore");

		assertEquals(202, send("POST", "/api/desktop/import/apply", "{\"id\":\"" + id + "\"}").statusCode());
		LibraryMaintenance.Outcome imported = outcome();

		assertTrue(imported.succeeded(), imported.message());
		assertEquals(LibraryMaintenance.Kind.IMPORT, imported.kind());
		assertTrue(imported.message().contains("X 登录不会导入"), imported.message());
		assertEquals(oldRows, LegacyMigrationTest.asV7(LegacyMigrationTest.everyRow(jdbc(), v7Tables)), "the library is now the old one, ids included");
		assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM x_like_sync_account", Integer.class));
		assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM tag WHERE name = '导入前才有'", Integer.class));
		HttpResponse<String> original = send("GET", "/api/assets/21/content", null);
		assertEquals(200, original.statusCode(), "imported originals are served");
		assertEquals("image-bytes", original.body());
		Map<Path, byte[]> after = files(home.resolve("config"));
		assertEquals(config.keySet(), after.keySet());
		config.forEach((file, bytes) -> assertArrayEquals(bytes, after.get(file), file + " must not change"));
		assertEquals(home.resolve("recovery").resolve(id).resolve("previous"), imported.location());
		assertEquals(1, LegacyMigrationTest.library(imported.location()).queryForObject(
				"SELECT COUNT(*) FROM tag WHERE name = '导入前才有'", Integer.class), "the replaced library is kept as it was");
		assertEquals(oldRows, LegacyMigrationTest.everyRow(old, v7Tables), "the old library is only read");
		assertEquals(oldHistory, old.queryForList("SELECT * FROM flyway_schema_history"), "and stays at V7");
		Map<Path, byte[]> oldMediaAfter = files(workspace.resolve("old-media"));
		assertEquals(oldMedia.keySet(), oldMediaAfter.keySet());
		oldMedia.forEach((file, bytes) -> assertArrayEquals(bytes, oldMediaAfter.get(file)));
		assertEquals("IDLE", body(send("GET", "/api/desktop/restore", null)).get("phase").asString());
		old.execute("DROP ALL OBJECTS");
	}

	@Test
	void missingOrChangedOldMediaAreListedAndCannotBeImported() throws Exception {
		importPng("red.png", Color.RED);
		JdbcTemplate old = oldLibrary(null);
		Files.delete(workspace.resolve("old-media").resolve("2026-10").resolve("work.png"));

		body(send("POST", "/api/desktop/import/check", null));
		JsonNode failed = awaitPhase("FAILED");

		assertEquals("IMPORT", failed.get("kind").asString());
		assertTrue(failed.get("message").asString().contains("不能导入"), failed.toString());
		assertEquals(1, failed.get("importPreview").get("problemCount").asInt());
		assertEquals("缺少文件：2026-10/work.png", failed.get("importPreview").get("problems").get(0).asString());
		assertNoOperationLeft();
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertEquals(409, send("POST", "/api/desktop/import/apply", "{\"id\":\"20000101-000000-0000\"}").statusCode());
		old.execute("DROP ALL OBJECTS");
	}

	@Test
	void aRelativeMediaFolderIsAskedForAndACancelledCheckLeavesNothing() throws Exception {
		JdbcTemplate old = oldLibrary("storage");

		JsonNode closed = body(send("POST", "/api/desktop/import/check", null));
		assertEquals("IDLE", closed.get("phase").asString(), "closing the folder dialog cancels");
		assertEquals(List.of("storage"), mediaAskedFor, "the configured path is shown, not guessed");
		assertNoOperationLeft();

		legacyMedia = workspace.resolve("old-media");
		body(send("POST", "/api/desktop/import/check", null));
		JsonNode ready = awaitPhase("READY");
		assertEquals(legacyMedia.toString(), ready.get("importPreview").get("mediaRoot").asString());

		JsonNode cancelled = body(send("POST", "/api/desktop/restore/cancel", "{\"id\":\"" + ready.get("id").asString() + "\"}"));
		assertEquals("IDLE", cancelled.get("phase").asString());
		assertNoOperationLeft();
		saveTo = backups.resolve("after-cancel.zip");
		assertEquals(200, send("POST", "/api/desktop/backup", null).statusCode(), "the slot is free again");
		assertTrue(outcome().succeeded());
		old.execute("DROP ALL OBJECTS");
	}

	@Test
	void aSwitchThatFailsPutsThePreviousLibraryBack() throws Exception {
		importPng("red.png", Color.RED);
		JdbcTemplate old = oldLibrary(null);
		body(send("POST", "/api/desktop/import/check", null));
		String id = awaitPhase("READY").get("id").asString();
		// Data and media out work; bringing the old library's data in fails, so both are moved back.
		AtomicInteger renames = new AtomicInteger();
		mover = (from, to) -> {
			if (renames.incrementAndGet() == 3) {
				throw new java.nio.file.AccessDeniedException(from.toString());
			}
			Files.move(from, to);
		};

		maintenance.apply(LibraryMaintenance.Kind.IMPORT, id);
		LibraryMaintenance.Outcome outcome = outcome();

		assertFalse(outcome.succeeded());
		assertTrue(outcome.libraryOpen(), outcome.message());
		assertTrue(outcome.message().contains("已退回原来的图库"), outcome.message());
		assertEquals(1, jdbc().queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
		assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM illustration WHERE id = 11", Integer.class));
		old.execute("DROP ALL OBJECTS");
	}

	@Test
	void anImportWaitsWhileAnXLoginIsInProgress() throws Exception {
		JdbcTemplate old = oldLibrary(null);
		body(send("POST", "/api/desktop/import/check", null));
		String id = awaitPhase("READY").get("id").asString();
		startLogin();

		HttpResponse<String> refused = send("POST", "/api/desktop/import/apply", "{\"id\":\"" + id + "\"}");

		assertEquals(409, refused.statusCode());
		assertTrue(refused.body().contains("X 授权正在进行"), refused.body());
		assertEquals("READY", body(send("GET", "/api/desktop/restore", null)).get("phase").asString());
		old.execute("DROP ALL OBJECTS");
	}

	@Test
	void theOldPasswordAndUrlParametersReachNoResponseLogOrFile() throws Exception {
		int closed = freePort();
		String mediaRoot = workspace.toString().replace('\\', '/');
		List<String> secrets = new ArrayList<>(List.of("file-secret-123"));
		// A URL the app supports, to a closed port: the real driver fails, with a password of its own key.
		writeLegacyConfig("jdbc:mysql://127.0.0.1:" + closed + "/illustration_archive?useSSL=false&connectTimeout=2000",
				"file-secret-123", mediaRoot);

		HttpResponse<String> started = send("POST", "/api/desktop/import/check", null);
		assertEquals(200, started.statusCode(), started.body());
		JsonNode failed = awaitPhase("FAILED");
		String shown = failed.toString();
		assertTrue(failed.get("message").asString().contains("没能读取旧版图库的数据库"), shown);
		assertFalse(shown.contains("file-secret-123"), shown);
		// As the page does once it showed the refusal.
		body(send("POST", "/api/desktop/restore/cancel", "{\"id\":null}"));

		// What R34-C1 / C2 found: URL credentials that differ from the password key, with spaces or percent-encoding,
		// and values the driver repeats in its own errors. Each is refused before connecting, and nothing is copied.
		Map<String, String> urls = Map.of(
				"address-secret", "jdbc:mysql://address=(host=127.0.0.1)(port=" + closed + ")(user=u)(password = address-secret)/illustration_archive",
				"space-secret", "jdbc:mysql://127.0.0.1:" + closed + "/illustration_archive?password= space-secret",
				"encoded-secret", "jdbc:mysql://127.0.0.1:" + closed + "/illustration_archive?%70assword=encoded-secret",
				"fetch-secret", "jdbc:mysql://127.0.0.1:" + closed + "/illustration_archive?defaultFetchSize=fetch-secret",
				"charset-secret", "jdbc:mysql://127.0.0.1:" + closed + "/illustration_archive?passwordCharacterEncoding=charset-secret");
		secrets.addAll(urls.keySet());
		for (Map.Entry<String, String> url : urls.entrySet()) {
			writeLegacyConfig(url.getValue(), "file-secret-123", mediaRoot);
			HttpResponse<String> refused = send("POST", "/api/desktop/import/check", null);
			assertEquals(409, refused.statusCode(), url.getKey() + ": " + refused.body());
			assertTrue(refused.body().contains("没有连接"), refused.body());
			for (String secret : secrets) {
				assertFalse(refused.body().contains(secret), url.getKey() + ": " + refused.body());
			}
			assertEquals("IDLE", body(send("GET", "/api/desktop/restore", null)).get("phase").asString());
			assertNoOperationLeft();
		}
		// Only the config file itself may hold them; it is the user's own and is only read.
		Files.delete(legacyConfig);
		server.stop();
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
		assertTrue(Files.readString(home.resolve("logs").resolve("desktop.log"), StandardCharsets.UTF_8)
				.contains("A library could not be prepared"), "the failure itself was logged");
		try (Stream<Path> tree = Files.walk(workspace)) {
			for (Path file : tree.filter(Files::isRegularFile).toList()) {
				String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
				for (String secret : secrets) {
					assertFalse(content.contains(secret), secret + " found in " + file);
				}
			}
		}
	}

	@Test
	void anOldAppStoringItsMediaInS3IsRefusedBeforeAnythingIsCopied() throws Exception {
		legacyConfig = Files.writeString(workspace.resolve("application-local.properties"), """
				ILLUSTRATION_ARCHIVE_DB_URL=jdbc:mysql://127.0.0.1:1/illustration_archive
				ILLUSTRATION_ARCHIVE_DB_USERNAME=someone
				ILLUSTRATION_ARCHIVE_STORAGE_ROOT=/data/storage
				ILLUSTRATION_ARCHIVE_STORAGE_TYPE=s3
				""");

		HttpResponse<String> refused = send("POST", "/api/desktop/import/check", null);

		assertEquals(409, refused.statusCode());
		assertTrue(refused.body().contains("S3"), refused.body());
		assertEquals("IDLE", body(send("GET", "/api/desktop/restore", null)).get("phase").asString());
		assertTrue(mediaAskedFor.isEmpty());
	}

	private void writeLegacyConfig(String url, String password, String mediaRoot) throws IOException {
		legacyConfig = Files.writeString(workspace.resolve("application-local.properties"), String.join("\n",
				"ILLUSTRATION_ARCHIVE_DB_URL=" + url, "ILLUSTRATION_ARCHIVE_DB_USERNAME=someone",
				"ILLUSTRATION_ARCHIVE_DB_PASSWORD=" + password, "ILLUSTRATION_ARCHIVE_STORAGE_ROOT=" + mediaRoot, ""));
	}

	private void assertNoOperationLeft() throws IOException {
		Path recovery = home.resolve("recovery");
		if (Files.isDirectory(recovery)) {
			try (Stream<Path> operations = Files.list(recovery)) {
				assertEquals(List.of(), operations.toList(), "no prepared library is left behind");
			}
		}
	}

	private void startLogin() {
		try {
			HttpResponse<String> login = send("POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}");
			assertEquals("WAITING", body(login).get("status").get("state").asString());
		} catch (Exception failed) {
			throw new IllegalStateException(failed);
		}
	}

	private int deleteStatus(long illustration) {
		try {
			return send("DELETE", "/api/illustrations/" + illustration, null).statusCode();
		} catch (Exception failed) {
			throw new IllegalStateException(failed);
		}
	}

	private LibraryMaintenance.Outcome outcome() throws InterruptedException {
		LibraryMaintenance.Outcome outcome = outcomes.poll(90, TimeUnit.SECONDS);
		assertNotNull(outcome, "the operation should have finished");
		return outcome;
	}

	private String awaitReady() throws InterruptedException {
		for (int attempt = 0; attempt < 600; attempt++) {
			LibraryMaintenance.RestoreView view = maintenance.restoreView();
			if (view.phase() == LibraryMaintenance.Phase.READY) {
				return view.id();
			}
			assertTrue(view.phase() == LibraryMaintenance.Phase.CHECKING, String.valueOf(view));
			Thread.sleep(100);
		}
		throw new AssertionError("the check did not finish");
	}

	private JsonNode awaitPhase(String phase) throws Exception {
		for (int attempt = 0; attempt < 600; attempt++) {
			JsonNode view = body(send("GET", "/api/desktop/restore", null));
			if (phase.equals(view.get("phase").asString())) {
				return view;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("never reached " + phase);
	}

	private JdbcTemplate jdbc() {
		return server.running.context().getBean(JdbcTemplate.class);
	}

	private String openSession() throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(server.running.sessionUri()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, response.statusCode());
		String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
		return setCookie.substring(0, setCookie.indexOf(';'));
	}

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(server.running.baseUri().resolve(path))
				.timeout(Duration.ofSeconds(30)).header("Cookie", cookie).header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode body(HttpResponse<String> response) {
		assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
		return json.readTree(response.body());
	}

	private void importPng(String name, Color color) throws Exception {
		String boundary = "----maintenance-test";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name
				+ "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(png(color));
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> imported = http.send(HttpRequest.newBuilder(server.running.baseUri().resolve("/api/illustrations/import"))
				.header("Cookie", cookie).header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
		assertTrue(imported.body().contains("\"successCount\":1"), imported.body());
	}

	private static byte[] png(Color color) throws IOException {
		BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 64, 48);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static Map<Path, byte[]> files(Path folder) throws IOException {
		Map<Path, byte[]> files = new java.util.TreeMap<>();
		try (Stream<Path> tree = Files.walk(folder)) {
			for (Path file : tree.filter(Files::isRegularFile).toList()) {
				files.put(folder.relativize(file), Files.readAllBytes(file));
			}
		}
		return files;
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/** The launcher's server control, headless and on free ports; it can be told to fail the next starts. */
	private final class TestServer implements LibraryMaintenance.Server {

		private final String token = DesktopServer.newAccessToken();
		private final Map<String, String> overrides;
		final AtomicInteger failNextStarts = new AtomicInteger();
		/** Added to every count, as if the restored library held something other than the backup. */
		volatile long countOffset;
		/** Runs right after each start, before the maintenance goes on: the moment another tab could send a request. */
		volatile java.util.function.Consumer<Boolean> afterStart = held -> { };
		volatile DesktopServer.Running running;

		TestServer() throws IOException {
			overrides = Map.of("illustration-archive.desktop.x-callback-port", Integer.toString(freePort()),
					"illustration-archive.desktop.x-authorize-url", "http://127.0.0.1:9/authorize");
		}

		@Override
		public boolean running() {
			return running != null;
		}

		@Override
		public MaintenanceGate gate() {
			return running.context().getBean(MaintenanceGate.class);
		}

		@Override
		public boolean stop() {
			DesktopServer.Running current = running;
			running = null;
			return current == null || DesktopServer.stop(current);
		}

		@Override
		public void start(boolean held) {
			if (failNextStarts.getAndDecrement() > 0) {
				throw new IllegalStateException("A start that fails, as with a library that does not open.");
			}
			Map<String, String> properties = new java.util.HashMap<>(overrides);
			properties.put(MaintenanceGate.START_CLOSED_PROPERTY, Boolean.toString(held));
			DesktopServer.Running started = DesktopServer.start(home, 0, true, token, properties);
			started.context().getBean(MaintenanceController.class).connect(maintenance);
			running = started;
			afterStart.accept(held);
		}

		@Override
		public long count(String table) {
			return jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Long.class) + countOffset;
		}

		@Override
		public boolean loginInProgress() {
			return running.context().getBean(XAuthorizationService.class).status().state() == XAuthorizationService.State.WAITING;
		}
	}
}
