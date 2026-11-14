package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.dto.IllustrationBatchUpdateRequest;
import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.service.IllustrationBatchUpdateService;
import com.yuutara.illustrationarchive.service.IllustrationNotFoundException;
import com.yuutara.illustrationarchive.service.IllustrationTrashService;
import com.yuutara.illustrationarchive.service.IllustrationUpdateService;
import com.yuutara.illustrationarchive.service.XLikePersistenceService;
import com.yuutara.illustrationarchive.service.XPostDuplicateException;
import com.yuutara.illustrationarchive.service.XPostPersistenceService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-stack desktop Trash integration tests on real H2, Flyway V10, Tomcat, and MobileAccess:
 * soft delete, batch trash/restore, normal endpoint exclusion, duplicate-in-trash reporting for
 * local uploads and X archive/re-sync, permanent purge, V9->V10 upgrade, backup/restore, and
 * mobile LAN isolation.
 */
class TrashDesktopIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final LibraryBackup.Progress QUIET = (stage, fraction) -> { };

	@TempDir
	Path workspace;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<DesktopServer.Running> started = new ArrayList<>();

	@AfterEach
	void tearDown() {
		started.forEach(running -> {
			try {
				running.context().close();
			} catch (RuntimeException ignored) {
			}
		});
		started.clear();
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void endToEndTrashIsolationRestoreDuplicatesAndPurgeAcrossNormalEndpointsAndX() throws Exception {
		Path home = workspace.resolve("desktop-home");
		// Start from a V9 database with pre-existing work & x_like_item to verify V9 -> V10 upgrade
		Path dataDir = Files.createDirectories(home.resolve("data"));
		String h2Url = "jdbc:h2:file:" + dataDir.resolve("archive") + DesktopServer.H2_COMPATIBILITY + ";DB_CLOSE_ON_EXIT=FALSE";
		Flyway.configure().dataSource(h2Url, "sa", "").locations("classpath:db/migration-h2").target("9").load().migrate();
		JdbcTemplate preJdbc = new JdbcTemplate(new DriverManagerDataSource(h2Url, "sa", ""));
		preJdbc.update("INSERT INTO author (id, display_name, x_username, x_user_id) VALUES (5, 'LegacyArtist', 'legacy_art', 'u5')");
		preJdbc.update("INSERT INTO illustration (id, title, author_id) VALUES (50, 'LegacyWork', 5)");
		preJdbc.update("INSERT INTO x_like_item (id, x_post_id, x_author_id, author_username, author_display_name, post_text, status, discovered_at, updated_at, imported_illustration_id) "
				+ "VALUES (60, 'post-60', 'u5', 'legacy_art', 'LegacyArtist', 'text', 'IMPORTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 50)");

		DesktopServer.Running running = start(home, Map.of());
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		assertNull(jdbc.queryForObject("SELECT trashed_at FROM illustration WHERE id = 50", Object.class));
		assertEquals(50L, jdbc.queryForObject("SELECT imported_illustration_id FROM x_like_item WHERE id = 60", Long.class));

		String cookie = openSession(running);
		byte[] red = png(Color.RED);
		byte[] blue = png(Color.BLUE);
		byte[] green = png(Color.GREEN);
		long redWork = importSingle(running, cookie, "red.png", red);
		long blueWork = importSingle(running, cookie, "blue.png", blue);
		long greenWork = importSingle(running, cookie, "green.png", green);

		long redAsset = jdbc.queryForObject("SELECT id FROM asset WHERE illustration_id = ?", Long.class, redWork);
		long tagId = JSON.readTree(send(running.baseUri().resolve("/api/tags"), cookie, "POST", "{\"name\":\"TrashTag\"}").body())
				.get("id").asLong();
		long authorId = JSON.readTree(send(running.baseUri().resolve("/api/authors"), cookie, "POST",
				"{\"displayName\":\"BluePainter\",\"xUsername\":\"blue_art\"}").body()).get("id").asLong();
		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + redWork), cookie, "PATCH",
				"{\"title\":\"Red Study\",\"authorId\":" + authorId + ",\"tagIds\":[" + tagId + "]}").statusCode());
		jdbc.update("INSERT INTO x_like_item (x_post_id, x_author_id, author_username, author_display_name, post_text, status, discovered_at, updated_at, imported_illustration_id) "
				+ "VALUES ('post-red', 'x-blue', 'blue_art', 'BluePainter', 'hi', 'IMPORTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)", redWork);

		// Batch move redWork & blueWork to trash
		HttpResponse<String> batchTrash = send(running.baseUri().resolve("/api/illustrations/batch/trash"), cookie,
				"POST", "{\"ids\":[" + redWork + "," + blueWork + "]}");
		assertEquals(200, batchTrash.statusCode(), batchTrash.body());
		assertTrue(batchTrash.body().contains("\"movedCount\":2"), batchTrash.body());

		// Normal endpoints must exclude trashed works
		JsonNode gallery = JSON.readTree(send(running.baseUri().resolve("/api/illustrations"), cookie, "GET", null).body());
		assertEquals(2, gallery.get("totalElements").asInt()); // greenWork + LegacyWork(50)
		assertEquals(404, send(running.baseUri().resolve("/api/illustrations/" + redWork), cookie, "GET", null).statusCode());
		assertEquals(404, send(running.baseUri().resolve("/api/illustrations/" + redWork), cookie, "PATCH", "{\"title\":\"Nope\"}").statusCode());
		assertEquals(404, send(running.baseUri().resolve("/api/illustrations/batch/metadata"), cookie,
				"POST", "{\"ids\":[" + redWork + "," + greenWork + "],\"authorId\":" + authorId + "}").statusCode());
		JsonNode greenAfterMixed = JSON.readTree(send(running.baseUri().resolve("/api/illustrations/" + greenWork), cookie, "GET", null).body());
		assertTrue(greenAfterMixed.get("author").isNull(), "mixed batch with a trashed work must leave active works untouched");
		assertEquals("[]", send(running.baseUri().resolve("/api/illustrations/batch/tags"), cookie,
				"POST", "{\"ids\":[" + redWork + "]}").body());
		assertEquals(404, send(running.baseUri().resolve("/api/assets/" + redAsset + "/content"), cookie, "GET", null).statusCode());
		assertEquals(404, send(running.baseUri().resolve("/api/assets/" + redAsset + "/thumbnail"), cookie, "GET", null).statusCode());
		assertEquals(404, send(running.baseUri().resolve("/api/illustrations/" + redWork + "/ai-analysis"), cookie, "POST", null).statusCode());
		JsonNode byTag = JSON.readTree(send(running.baseUri().resolve("/api/illustrations?tagId=" + tagId), cookie, "GET", null).body());
		assertEquals(0, byTag.get("totalElements").asInt(), "trashed works are excluded from tag-filtered gallery reads");
		JsonNode byAuthor = JSON.readTree(send(running.baseUri().resolve("/api/illustrations?authorId=" + authorId), cookie, "GET", null).body());
		assertEquals(0, byAuthor.get("totalElements").asInt(), "trashed works are excluded from author-filtered gallery reads");

		// Trash list & asset endpoints serve trashed works and 30-day schedule
		JsonNode trashList = JSON.readTree(send(running.baseUri().resolve("/api/trash"), cookie, "GET", null).body());
		assertEquals(2, trashList.get("totalItems").asInt());
		assertEquals(30, trashList.get("retentionDays").asInt());
		JsonNode redTrashItem = null;
		for (JsonNode item : trashList.get("items")) {
			if (item.get("id").asLong() == redWork) redTrashItem = item;
		}
		assertNotNull(redTrashItem);
		Instant trashedAt = Instant.parse(redTrashItem.get("trashedAt").asString());
		Instant purgeAt = Instant.parse(redTrashItem.get("purgeAt").asString());
		assertEquals(Duration.ofDays(30), Duration.between(trashedAt, purgeAt));
		HttpResponse<byte[]> trashedBytes = http.send(HttpRequest.newBuilder(
						running.baseUri().resolve("/api/trash/assets/" + redAsset + "/content"))
				.header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, trashedBytes.statusCode());
		assertArrayEquals(red, trashedBytes.body());
		assertEquals(200, send(running.baseUri().resolve("/api/trash/assets/" + redAsset + "/thumbnail"), cookie, "GET", null).statusCode());

		// Duplicate detection for local manual import (single & batch)
		HttpResponse<String> singleDup = uploadMultipart(running, cookie, "/api/illustrations/import/single", "red.png", red);
		assertEquals(409, singleDup.statusCode(), singleDup.body());
		assertTrue(singleDup.body().contains("\"code\":\"DUPLICATE_IN_TRASH\""), singleDup.body());
		assertTrue(singleDup.body().contains("\"illustrationId\":" + redWork), singleDup.body());

		HttpResponse<String> batchDup = uploadMultipart(running, cookie, "/api/illustrations/import", "red.png", red);
		assertEquals(200, batchDup.statusCode(), batchDup.body());
		assertTrue(batchDup.body().contains("\"errorCode\":\"DUPLICATE_IN_TRASH\""), batchDup.body());
		assertTrue(batchDup.body().contains("\"illustrationId\":" + redWork), batchDup.body());

		// Duplicate detection in Trash for X import persistence & X re-sync summary
		String redSha = sha256Hex(red);
		XPostPersistenceService xPostPersistence = running.context().getBean(XPostPersistenceService.class);
		jdbc.update("INSERT INTO x_like_item (id, x_post_id, x_author_id, author_username, author_display_name, post_text, status, discovered_at, updated_at) "
				+ "VALUES (777, 'post-777', 'u77', 'artist77', 'Artist77', 'hi', 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
		var importItem = new com.yuutara.illustrationarchive.repository.XLikeRepository.ImportItem(
				777L, "post-777", "u77", "artist77", "Artist77", XLikeStatus.PENDING, null);
		XPostDuplicateException xDup = assertThrows(XPostDuplicateException.class, () -> xPostPersistence.persist(
				importItem,
				List.of(new XLikeMedia("m1", 0, "photo", "https://pbs.twimg.com/media/1.jpg", 64, 48)),
				List.of(new StoredFile("red.png", "trashed/red.png", "image/png", red.length, redSha))));
		assertTrue(xDup.trashed());
		assertEquals(redWork, xDup.illustrationId());

		XLikePersistenceService xLikePersistence = running.context().getBean(XLikePersistenceService.class);
		var resyncCandidate = new XLikeCandidate("post-red", "x-blue", "blue_art", "BluePainter", "hi",
				Instant.parse("2026-10-02T00:00:00Z"), XLikeStatus.PENDING,
				List.of(new XLikeMedia("mr", 0, "photo", "https://pbs.twimg.com/media/r.jpg", 64, 48)));
		var resyncSummary = xLikePersistence.savePage(new XLikePage(List.of(resyncCandidate), false, null));
		assertEquals(1, resyncSummary.existingCount());
		assertEquals(1, resyncSummary.trashedExistingCount());
		assertEquals("IMPORTED", jdbc.queryForObject("SELECT status FROM x_like_item WHERE x_post_id = 'post-red'", String.class));
		assertEquals(redWork, jdbc.queryForObject("SELECT imported_illustration_id FROM x_like_item WHERE x_post_id = 'post-red'", Long.class));

		// Restore redWork: preserves ID, author, tags, SHA-256, bytes, and x_like_item link
		HttpResponse<String> restoreResp = send(running.baseUri().resolve("/api/trash/restore"), cookie,
				"POST", "{\"ids\":[" + redWork + "]}");
		assertEquals(200, restoreResp.statusCode(), restoreResp.body());
		assertTrue(restoreResp.body().contains("\"restoredCount\":1"), restoreResp.body());
		JsonNode restoredDetail = JSON.readTree(send(running.baseUri().resolve("/api/illustrations/" + redWork), cookie, "GET", null).body());
		assertEquals("Red Study", restoredDetail.get("title").asString());
		assertEquals("BluePainter", restoredDetail.get("author").get("displayName").asString());
		assertEquals("TrashTag", restoredDetail.get("tags").get(0).get("name").asString());
		assertEquals(redWork, jdbc.queryForObject("SELECT imported_illustration_id FROM x_like_item WHERE x_post_id = 'post-red'", Long.class));

		// Delete already-missing file on disk for blueWork, then purge-all: succeeds cleanly & removes DB row
		String blueKey = jdbc.queryForObject("SELECT storage_key FROM asset WHERE illustration_id = ?", String.class, blueWork);
		Files.deleteIfExists(home.resolve("media").resolve(blueKey));
		HttpResponse<String> purgeAll = send(running.baseUri().resolve("/api/trash/purge-all"), cookie, "POST", null);
		assertEquals(200, purgeAll.statusCode(), purgeAll.body());
		assertTrue(purgeAll.body().contains("\"purgedCount\":1"), purgeAll.body());
		assertTrue(purgeAll.body().contains("\"pendingFileCleanups\":0"), purgeAll.body());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM illustration WHERE id = ?", Integer.class, blueWork));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pending_asset_cleanup", Integer.class));
	}

	@Test
	void mobileLaneRejectsTrashRoutesAndTrashedWorks() throws Exception {
		Assumptions.assumeFalse(LanAddresses.siteLocal().isEmpty(), "LAN interface needed for mobile connector");
		Path home = workspace.resolve("mobile-home");
		DesktopServer.Running running = start(home, Map.of());
		String desktopCookie = openSession(running);
		assertEquals(200, send(running.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie, "PUT",
				"{\"enabled\":true}").statusCode());
		long workId = importSingle(running, desktopCookie, "work.png", png(Color.MAGENTA));
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		long assetId = jdbc.queryForObject("SELECT id FROM asset WHERE illustration_id = ?", Long.class, workId);

		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + workId), desktopCookie, "DELETE", null).statusCode());

		MobileAccess mobile = running.context().getBean(MobileAccess.class);
		MobileAccess.PairingView pairing = mobile.startPairing(null);
		URI phone = URI.create("http://" + mobile.status().address() + ":" + mobile.status().port() + "/");
		HttpResponse<String> paired = send(phone.resolve("/m/pair?code=" + pairing.code()), null, "GET", null);
		String rawSetCookie = paired.headers().firstValue("Set-Cookie").orElseThrow();
		String phoneCookie = rawSetCookie.substring(0, rawSetCookie.indexOf(';'));

		for (String[] req : List.of(
				new String[] {"GET", "/api/trash"},
				new String[] {"POST", "/api/illustrations/batch/trash"},
				new String[] {"POST", "/api/trash/restore"},
				new String[] {"POST", "/api/trash/purge"},
				new String[] {"POST", "/api/trash/purge-all"},
				new String[] {"GET", "/api/trash/assets/" + assetId + "/content"},
				new String[] {"GET", "/c/trash.html"},
				new String[] {"DELETE", "/api/illustrations/" + workId},
				new String[] {"POST", "/api/illustrations/" + workId + "/ai-analysis"}
		)) {
			HttpResponse<String> resp = send(phone.resolve(req[1]), phoneCookie, req[0], "POST".equals(req[0]) ? "{\"ids\":[1]}" : null);
			assertEquals(403, resp.statusCode(), req[0] + " " + req[1] + " -> " + resp.body());
		}

		// Trashed work must also not be readable from active gallery/asset endpoints on mobile
		assertEquals(404, send(phone.resolve("/api/illustrations/" + workId), phoneCookie, "GET", null).statusCode());
		assertEquals(404, send(phone.resolve("/api/assets/" + assetId + "/content"), phoneCookie, "GET", null).statusCode());
	}

	@Test
	void backupAndRestorePreserveTrashedWorksAndMaintenanceGateExcludesBackgroundPurge() throws Exception {
		Path home = workspace.resolve("backup-home");
		Path backupZip = workspace.resolve("backup.zip");
		DesktopServer.Running running = start(home, Map.of());
		String cookie = openSession(running);
		long activeWork = importSingle(running, cookie, "active.png", png(Color.CYAN));
		long trashedWork = importSingle(running, cookie, "trashed.png", png(Color.ORANGE));

		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + trashedWork), cookie, "DELETE", null).statusCode());
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		String trashedKey = jdbc.queryForObject("SELECT storage_key FROM asset WHERE illustration_id = ?", String.class, trashedWork);
		jdbc.update("INSERT INTO pending_asset_cleanup (storage_key, mime_type, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
				"2026-10/already-gone.png", "image/png");

		// MaintenanceGate mutual exclusion check while running
		MaintenanceGate gate = running.context().getBean(MaintenanceGate.class);
		IllustrationTrashService trashService = running.context().getBean(IllustrationTrashService.class);
		assertTrue(gate.tryEnterBackground());
		AtomicBoolean closeFinished = new AtomicBoolean(false);
		CountDownLatch closingStarted = new CountDownLatch(1);
		Thread closingThread = new Thread(() -> {
			closingStarted.countDown();
			try {
				gate.close(Duration.ofSeconds(10), () -> false);
			} catch (Exception ignored) {
			}
			closeFinished.set(true);
		});
		closingThread.start();
		assertTrue(closingStarted.await(3, TimeUnit.SECONDS));
		Thread.sleep(60);
		assertFalse(closeFinished.get(), "close must wait for active background trash purge/cleanup");
		gate.leaveBackground();
		closingThread.join(5000);
		assertTrue(closeFinished.get());
		assertFalse(trashService.purgeExpiredAndCleanup(() -> false).executed(), "closed gate rejects background purge");
		gate.open();

		running.context().close();
		started.clear();

		LibraryBackup.create(home, backupZip, "0.6.0", QUIET);
		// Wipe media & drop active/trashed rows to confirm restore brings both back cleanly
		Files.deleteIfExists(home.resolve("media").resolve(trashedKey));
		LibraryRestore.Candidate candidate = LibraryRestore.prepare(home, backupZip, QUIET);
		LibraryRestore.switchTo(home, candidate.id());
		LibraryRestore.confirm(home, candidate.id());

		DesktopServer.Running restored = start(home, Map.of());
		String restoredCookie = openSession(restored);
		assertEquals(200, send(restored.baseUri().resolve("/api/illustrations/" + activeWork), restoredCookie, "GET", null).statusCode());
		assertEquals(404, send(restored.baseUri().resolve("/api/illustrations/" + trashedWork), restoredCookie, "GET", null).statusCode());
		JsonNode trashAfterRestore = JSON.readTree(send(restored.baseUri().resolve("/api/trash"), restoredCookie, "GET", null).body());
		assertEquals(1, trashAfterRestore.get("totalItems").asInt());
		assertEquals(trashedWork, trashAfterRestore.get("items").get(0).get("id").asLong());
		assertTrue(Files.isRegularFile(home.resolve("media").resolve(trashedKey)));

		// Startup scheduler / explicit retry cleans up pending missing entry safely post-restore
		restored.context().getBean(IllustrationTrashService.class).retryPendingCleanups();
		assertEquals(0, restored.context().getBean(JdbcTemplate.class)
				.queryForObject("SELECT COUNT(*) FROM pending_asset_cleanup", Integer.class));
	}

	@Test
	void editsSerializeWithMoveToTrashForBothSinglePatchAndBatchAndMixedBatchLeavesAllUntouched() throws Exception {
		Path home = workspace.resolve("race-home");
		DesktopServer.Running running = start(home, Map.of());
		String cookie = openSession(running);
		long w1 = importSingle(running, cookie, "w1.png", png(Color.RED));
		long w2 = importSingle(running, cookie, "w2.png", png(Color.BLUE));
		long w3 = importSingle(running, cookie, "w3.png", png(Color.GREEN));
		long w4 = importSingle(running, cookie, "w4.png", png(Color.YELLOW));
		long t1 = JSON.readTree(send(running.baseUri().resolve("/api/tags"), cookie, "POST", "{\"name\":\"RaceTag1\"}").body()).get("id").asLong();
		long t2 = JSON.readTree(send(running.baseUri().resolve("/api/tags"), cookie, "POST", "{\"name\":\"RaceTag2\"}").body()).get("id").asLong();
		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + w1), cookie, "PATCH", "{\"tagIds\":[" + t1 + "]}").statusCode());
		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + w2), cookie, "PATCH", "{\"tagIds\":[" + t1 + "]}").statusCode());
		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + w3), cookie, "PATCH", "{\"tagIds\":[" + t1 + "]}").statusCode());

		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		TransactionTemplate tx = new TransactionTemplate(running.context().getBean(PlatformTransactionManager.class));
		IllustrationTrashService trashService = running.context().getBean(IllustrationTrashService.class);
		IllustrationBatchUpdateService batchUpdateService = running.context().getBean(IllustrationBatchUpdateService.class);
		IllustrationUpdateService updateService = running.context().getBean(IllustrationUpdateService.class);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			// Case 1: moveToTrash holds the row lock on w1 & w2 before batch tag removal runs -> batch rejects whole mixed batch (w1, w3) and preserves all tags
			CountDownLatch lockedForTrash = new CountDownLatch(1);
			CountDownLatch releaseTrash = new CountDownLatch(1);
			Future<?> trashWin = pool.submit(() -> tx.executeWithoutResult(status -> {
				jdbc.queryForList("SELECT id FROM illustration WHERE id = ? FOR UPDATE", Long.class, w1);
				lockedForTrash.countDown();
				awaitLatch(releaseTrash);
				jdbc.update("UPDATE illustration SET trashed_at = CURRENT_TIMESTAMP WHERE id = ?", w1);
			}));
			assertTrue(lockedForTrash.await(5, TimeUnit.SECONDS));

			IllustrationBatchUpdateRequest mixedBatch = new IllustrationBatchUpdateRequest();
			mixedBatch.setIds(List.of(w1, w3));
			mixedBatch.setRemoveTagIds(List.of(t1));
			mixedBatch.setAddTagIds(List.of(t2));
			Future<?> batchFuture = pool.submit(() -> assertThrows(IllustrationNotFoundException.class,
					() -> batchUpdateService.update(mixedBatch)));
			Thread.sleep(100);
			assertFalse(batchFuture.isDone(), "batch edit must wait on the illustration row lock");
			releaseTrash.countDown();
			trashWin.get(5, TimeUnit.SECONDS);
			batchFuture.get(5, TimeUnit.SECONDS);

			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM illustration_tag WHERE illustration_id = ? AND tag_id = ?", Integer.class, w1, t1));
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM illustration_tag WHERE illustration_id = ? AND tag_id = ?", Integer.class, w3, t1));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM illustration_tag WHERE illustration_id = ? AND tag_id = ?", Integer.class, w3, t2));

			// Case 2: moveToTrash locks w2 before single PATCH runs -> single PATCH fails and leaves w2's existing tags untouched
			CountDownLatch lockedSingle = new CountDownLatch(1);
			CountDownLatch releaseSingle = new CountDownLatch(1);
			Future<?> trashW2 = pool.submit(() -> tx.executeWithoutResult(status -> {
				jdbc.queryForList("SELECT id FROM illustration WHERE id = ? FOR UPDATE", Long.class, w2);
				lockedSingle.countDown();
				awaitLatch(releaseSingle);
				jdbc.update("UPDATE illustration SET trashed_at = CURRENT_TIMESTAMP WHERE id = ?", w2);
			}));
			assertTrue(lockedSingle.await(5, TimeUnit.SECONDS));

			IllustrationPatchRequest clearTags = new IllustrationPatchRequest();
			clearTags.setTagIds(List.of());
			Future<?> patchFuture = pool.submit(() -> assertThrows(IllustrationNotFoundException.class,
					() -> updateService.updateBasicMetadata(w2, clearTags)));
			Thread.sleep(100);
			assertFalse(patchFuture.isDone(), "single PATCH must wait on the illustration row lock");
			releaseSingle.countDown();
			trashW2.get(5, TimeUnit.SECONDS);
			patchFuture.get(5, TimeUnit.SECONDS);

			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM illustration_tag WHERE illustration_id = ? AND tag_id = ?", Integer.class, w2, t1));

			// Case 3: single PATCH locks w4 first -> concurrent moveToTrash waits until edited tags commit
			CountDownLatch editLocked = new CountDownLatch(1);
			CountDownLatch releaseEdit = new CountDownLatch(1);
			IllustrationPatchRequest editW4 = new IllustrationPatchRequest();
			editW4.setTagIds(List.of(t2));
			Future<?> editFirst = pool.submit(() -> tx.executeWithoutResult(status -> {
				updateService.updateBasicMetadata(w4, editW4);
				editLocked.countDown();
				awaitLatch(releaseEdit);
			}));
			assertTrue(editLocked.await(5, TimeUnit.SECONDS));

			Future<?> moveW4 = pool.submit(() -> trashService.moveToTrash(w4));
			Thread.sleep(100);
			assertFalse(moveW4.isDone(), "moveToTrash must wait until in-flight single edit commits");
			releaseEdit.countDown();
			editFirst.get(5, TimeUnit.SECONDS);
			moveW4.get(5, TimeUnit.SECONDS);

			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM illustration_tag WHERE illustration_id = ? AND tag_id = ?", Integer.class, w4, t2));
			assertNotNull(jdbc.queryForObject("SELECT trashed_at FROM illustration WHERE id = ?", Object.class, w4));
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void purgeThroughWindowsJunctionPreservesOutsideFileAndPendingRecordUntilRepaired() throws Exception {
		Path home = workspace.resolve("junction-home");
		Path outsideDir = Files.createDirectories(workspace.resolve("outside-media"));
		Path outsideFile = Files.write(outsideDir.resolve("keep.png"), png(Color.PINK));
		DesktopServer.Running running = start(home, Map.of());
		String cookie = openSession(running);
		long workId = importSingle(running, cookie, "work.png", png(Color.BLACK));

		assertEquals(204, send(running.baseUri().resolve("/api/illustrations/" + workId), cookie, "DELETE", null).statusCode());
		Path monthJunction = Files.createDirectories(home.resolve("media")).resolve("2099-01");
		Process junctionProc = new ProcessBuilder("cmd", "/c", "mklink", "/J", monthJunction.toString(), outsideDir.toString())
				.redirectErrorStream(true).start();
		assertEquals(0, junctionProc.waitFor(), new String(junctionProc.getInputStream().readAllBytes()));

		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		jdbc.update("UPDATE asset SET storage_key = '2099-01/keep.png' WHERE illustration_id = ?", workId);

		HttpResponse<String> purgeResp = send(running.baseUri().resolve("/api/trash/purge"), cookie, "POST", "{\"ids\":[" + workId + "]}");
		assertEquals(200, purgeResp.statusCode(), purgeResp.body());
		assertTrue(purgeResp.body().contains("\"pendingFileCleanups\":1"), purgeResp.body());
		assertTrue(Files.exists(outsideFile), "file beyond junction must not be deleted");
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pending_asset_cleanup WHERE storage_key = '2099-01/keep.png'", Integer.class));

		// Remove junction & place normal file under media/2099-01/keep.png, then retry cleanup
		Files.delete(monthJunction);
		Path realTarget = Files.createDirectories(monthJunction).resolve("keep.png");
		Files.write(realTarget, png(Color.BLACK));
		assertEquals(1, running.context().getBean(IllustrationTrashService.class).retryPendingCleanups());
		assertFalse(Files.exists(realTarget));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pending_asset_cleanup", Integer.class));
	}

	@Test
	void desktopShutdownDrainRejectsNewWorkCancelsOnTimeoutAndReopensCleanly() throws Exception {
		Path home = workspace.resolve("shutdown-gate-home");
		DesktopServer.Running running = start(home, Map.of());
		MaintenanceGate gate = running.context().getBean(MaintenanceGate.class);

		assertTrue(gate.tryEnterBackground());
		assertFalse(DesktopLauncher.drainBeforeShutdown(running, Duration.ofMillis(150), () -> false),
				"timeout while background cleanup is active cancels shutdown");
		assertFalse(gate.tryEnterBackground(), "MaintenanceGate stays closed refusing new work while draining/refused");

		gate.leaveBackground();
		DesktopLauncher.resumeAfterCancelledShutdown(running);
		assertTrue(gate.tryEnterBackground(), "after cancelling shutdown, resumed app accepts work again");
		gate.leaveBackground();

		assertTrue(DesktopLauncher.drainBeforeShutdown(running, Duration.ofSeconds(5), () -> false));
		assertFalse(gate.tryEnterBackground(), "gate closes before DB pool shutdown");
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertTrue(latch.await(10, TimeUnit.SECONDS));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	private DesktopServer.Running start(Path home, Map<String, String> overrides) {
		DesktopServer.Running running = DesktopServer.start(home, 0, true, overrides);
		started.add(running);
		return running;
	}

	private String openSession(DesktopServer.Running running) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(running.sessionUri()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, response.statusCode());
		String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
		return setCookie.substring(0, setCookie.indexOf(';'));
	}

	private HttpResponse<String> send(URI uri, String cookie, String method, String json) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
		if (cookie != null) builder.header("Cookie", cookie);
		if (json != null) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(json));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private long importSingle(DesktopServer.Running running, String cookie, String filename, byte[] bytes) throws Exception {
		HttpResponse<String> response = uploadMultipart(running, cookie, "/api/illustrations/import/single", filename, bytes);
		assertEquals(200, response.statusCode(), response.body());
		return JSON.readTree(response.body()).get("illustrationId").asLong();
	}

	private HttpResponse<String> uploadMultipart(DesktopServer.Running running, String cookie, String path,
			String filename, byte[] bytes) throws Exception {
		String boundary = "----trash-test-" + System.nanoTime();
		String fieldName = "/api/illustrations/import".equals(path) ? "files" : "file";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
				+ filename + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(bytes);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(running.baseUri().resolve(path))
				.header("Cookie", cookie)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static byte[] png(Color color) throws IOException {
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 32, 32);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
