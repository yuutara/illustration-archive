package com.yuutara.illustrationarchive.desktop;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated H2 + real Tomcat + mock X CDN integration tests for mobile X Likes organization,
 * permission gating, same-origin enforcement, proxy failures, archive retries, and desktop/phone concurrency.
 */
class MobileXIntegrationTest {

	@TempDir
	Path home;

	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<DesktopServer.Running> started = new ArrayList<>();
	private final List<HttpServer> servers = new ArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		Path settings = home.resolve("config").resolve("settings.properties");
		if (Files.exists(settings) && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			try { Files.setAttribute(settings, "dos:readonly", false); } catch (IOException ignored) { }
		}
		for (var running : started) {
			try { running.context().close(); } catch (RuntimeException ignored) { }
		}
		for (var server : servers) {
			server.stop(0);
		}
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void xInboxDefaultsOffAndOnlyAllowsOrganizingEndpointsWhenExplicitlyEnabled() throws Exception {
		assumeLan();
		MockCdn cdn = startMockCdn(png(Color.CYAN, 64, 48));
		DesktopServer.Running running = startServer(cdn);
		String desktopCookie = openDesktopSession(running);
		String phoneCookie = pairPhone(running);
		URI phone = phoneBase(running);
		long itemId = seedLike(running, "p-1", 10L, "alice", "photo", "https://pbs.twimg.com/media/Pic1.png", 64, 48);

		// Default is off: desktop status says false, capabilities says false, gallery stays 200, X inbox routes return 403.
		JsonNode mobileStatus = json.readTree(desktopGet(running, desktopCookie, "/api/desktop/mobile").body());
		assertFalse(mobileStatus.path("xInboxEnabled").asBoolean());

		HttpResponse<String> capsOff = phoneGet(phone, phoneCookie, "/api/m/capabilities");
		assertEquals(200, capsOff.statusCode());
		assertFalse(json.readTree(capsOff.body()).path("xInboxEnabled").asBoolean());
		assertEquals(200, phoneGet(phone, phoneCookie, "/api/illustrations").statusCode());

		assertEquals(403, phoneGet(phone, phoneCookie, "/m/x.html").statusCode());
		assertEquals(403, phoneGet(phone, phoneCookie, "/m/x.js").statusCode());
		assertEquals(403, phoneGet(phone, phoneCookie, "/c/c-x-model.js").statusCode());

		for (String blockedGet : List.of("/api/x-import/inbox", "/api/x-import/inbox/skipped",
				"/api/x-import/inbox/" + itemId + "/media/mk-1/content?size=small")) {
			HttpResponse<String> resp = phoneGet(phone, phoneCookie, blockedGet);
			assertEquals(403, resp.statusCode(), blockedGet);
			assertTrue(resp.body().contains("MOBILE_X_DISABLED"), resp.body());
		}
		RawResponse blockedWrite = rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + itemId + "]}");
		assertEquals(403, blockedWrite.status);
		assertTrue(blockedWrite.body.contains("MOBILE_X_DISABLED"));

		// Explicitly turn on from desktop.
		HttpResponse<String> enabled = desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":true}");
		assertEquals(200, enabled.statusCode(), enabled.body());
		assertTrue(json.readTree(enabled.body()).path("xInboxEnabled").asBoolean());

		HttpResponse<String> capsOn = phoneGet(phone, phoneCookie, "/api/m/capabilities");
		assertEquals(200, capsOn.statusCode());
		assertTrue(json.readTree(capsOn.body()).path("xInboxEnabled").asBoolean());
		assertEquals(200, phoneGet(phone, phoneCookie, "/m/x.html").statusCode());
		assertEquals(200, phoneGet(phone, phoneCookie, "/m/x.js").statusCode());
		assertEquals(200, phoneGet(phone, phoneCookie, "/c/c-x-model.js").statusCode());
		assertEquals(200, phoneGet(phone, phoneCookie, "/api/x-import/inbox").statusCode());

		// Non-organizing desktop / X / gallery-write routes stay 403 even while mobile X inbox is on.
		assertEquals(403, phoneGet(phone, phoneCookie, "/api/x-import/sync/status").statusCode());
		assertEquals(403, phoneGet(phone, phoneCookie, "/api/x-auth/status").statusCode());
		assertEquals(403, phoneGet(phone, phoneCookie, "/api/desktop/settings").statusCode());
		assertEquals(403, rawPhone(phone, phoneCookie, "POST", "/api/x-import/sync", sameOriginHeaders(phone), "").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "POST", "/api/x-auth/start", sameOriginHeaders(phone), "{}").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "PUT", "/api/desktop/mobile/x-inbox", sameOriginHeaders(phone), "{\"enabled\":false}").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "POST", "/api/illustrations", sameOriginHeaders(phone), "{}").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "DELETE", "/api/illustrations/1", sameOriginHeaders(phone), "").status);
	}

	@Test
	void proxiesSmallLargeAndGifFromMockCdnRejectsUpstreamErrorsAndRetriesArchive() throws Exception {
		assumeLan();
		byte[] pngBytes = png(Color.MAGENTA, 80, 60);
		MockCdn cdn = startMockCdn(pngBytes);
		DesktopServer.Running running = startServer(cdn);
		String desktopCookie = openDesktopSession(running);
		String phoneCookie = pairPhone(running);
		URI phone = phoneBase(running);
		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":true}");

		long photoId = seedLike(running, "p-photo", 20L, "painter", "photo", "https://pbs.twimg.com/media/Art1.png", 80, 60);
		long dupId = seedLike(running, "p-dup", 15L, "painter2", "photo", "https://pbs.twimg.com/media/Art2.png", 80, 60);
		long gifId = seedLike(running, "p-gif", 10L, "animator", "animated_gif", "https://video.twimg.com/tweet_video/Clip.mp4", 80, 60);

		// Proxy small & large photo variants
		HttpResponse<byte[]> small = phoneGetBytes(phone, phoneCookie, "/api/x-import/inbox/" + photoId + "/media/mk-1/content?size=small");
		assertEquals(200, small.statusCode());
		assertEquals("image/png", small.headers().firstValue("Content-Type").orElseThrow());
		assertEquals("no-store", small.headers().firstValue("Cache-Control").orElseThrow());
		assertEquals("nosniff", small.headers().firstValue("X-Content-Type-Options").orElseThrow());
		assertArrayEquals(pngBytes, small.body());

		HttpResponse<byte[]> large = phoneGetBytes(phone, phoneCookie, "/api/x-import/inbox/" + photoId + "/media/mk-1/content?size=large");
		assertEquals(200, large.statusCode());
		assertArrayEquals(pngBytes, large.body());

		// Proxy animated_gif
		HttpResponse<byte[]> gif = phoneGetBytes(phone, phoneCookie, "/api/x-import/inbox/" + gifId + "/media/mk-1/content");
		assertEquals(200, gif.statusCode());
		assertEquals("video/mp4", gif.headers().firstValue("Content-Type").orElseThrow());
		assertArrayEquals(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}, gif.body());

		assertTrue(cdn.seen.contains("/media/Art1?format=png&name=small"));
		assertTrue(cdn.seen.contains("/media/Art1?format=png&name=large"));
		assertTrue(cdn.seen.contains("/tweet_video/Clip.mp4"));

		// Invalid preview size -> 400; CDN 500 or 302 redirect -> 502 (never passes upstream body/redirect through)
		assertEquals(400, phoneGet(phone, phoneCookie, "/api/x-import/inbox/" + photoId + "/media/mk-1/content?size=orig").statusCode());
		cdn.photoStatus.set(500);
		assertEquals(502, phoneGet(phone, phoneCookie, "/api/x-import/inbox/" + photoId + "/media/mk-1/content?size=large").statusCode());
		cdn.photoStatus.set(302);
		assertEquals(502, phoneGet(phone, phoneCookie, "/api/x-import/inbox/" + photoId + "/media/mk-1/content?size=small").statusCode());

		// Archive failure when mock CDN fails full-resolution download, then recover & retry cleanly
		RawResponse failedArchive = rawPhone(phone, phoneCookie, "POST", "/api/x-import/inbox/import",
				sameOriginHeaders(phone), "{\"itemIds\":[" + photoId + "]}");
		assertEquals(200, failedArchive.status);
		JsonNode failJson = json.readTree(failedArchive.body);
		assertEquals(1, failJson.path("failureCount").asInt());
		assertEquals("FAILED", failJson.path("items").get(0).path("status").asText());
		assertEquals(0, count(running, "SELECT COUNT(*) FROM illustration"));

		cdn.photoStatus.set(200);
		RawResponse retryArchive = rawPhone(phone, phoneCookie, "POST", "/api/x-import/inbox/import",
				sameOriginHeaders(phone), "{\"itemIds\":[" + photoId + "]}");
		assertEquals(200, retryArchive.status);
		JsonNode okJson = json.readTree(retryArchive.body);
		assertEquals(1, okJson.path("successCount").asInt());
		assertEquals("SUCCESS", okJson.path("items").get(0).path("status").asText());
		assertTrue(cdn.seen.contains("/media/Art1?format=png&name=4096x4096"));
		assertEquals(1, count(running, "SELECT COUNT(*) FROM illustration"));

		// Identical image bytes from another post report DUPLICATE
		RawResponse dupArchive = rawPhone(phone, phoneCookie, "POST", "/api/x-import/inbox/import",
				sameOriginHeaders(phone), "{\"itemIds\":[" + dupId + "]}");
		assertEquals(200, dupArchive.status);
		assertEquals(1, json.readTree(dupArchive.body).path("duplicateCount").asInt());
		assertEquals("DUPLICATE", json.readTree(dupArchive.body).path("items").get(0).path("status").asText());
	}

	@Test
	void skipUndoArchiveSelectedAndSkipRestAndRecentlySkippedRestore() throws Exception {
		assumeLan();
		MockCdn cdn = startMockCdn(png(Color.ORANGE, 64, 48));
		DesktopServer.Running running = startServer(cdn);
		String desktopCookie = openDesktopSession(running);
		String phoneCookie = pairPhone(running);
		URI phone = phoneBase(running);
		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":true}");

		long i1 = seedLike(running, "s-1", 40L, "u1", "photo", "https://pbs.twimg.com/media/S1.png", 64, 48);
		long i2 = seedLike(running, "s-2", 30L, "u2", "photo", "https://pbs.twimg.com/media/S2.png", 64, 48);
		long i3 = seedLike(running, "s-3", 20L, "u3", "photo", "https://pbs.twimg.com/media/S3.png", 64, 48);

		// Skip i3 then test wrong skipToken vs valid skipToken undo
		RawResponse skipRes = rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + i3 + "]}");
		assertEquals(200, skipRes.status);
		String token = json.readTree(skipRes.body).path("skipToken").asText();
		assertFalse(token.isBlank());

		RawResponse wrongUndo = rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/unskip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + i3 + "],\"skipToken\":\"stale-token\"}");
		assertEquals(0, json.readTree(wrongUndo.body).path("restoredCount").asInt());

		RawResponse rightUndo = rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/unskip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + i3 + "],\"skipToken\":\"" + token + "\"}");
		assertEquals(1, json.readTree(rightUndo.body).path("restoredCount").asInt());

		// Archive-selected & skip-rest pattern: skip [i2, i3], archive [i1], then restore i2 from skipped list
		assertEquals(200, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + i2 + "," + i3 + "]}").status);
		assertEquals(200, rawPhone(phone, phoneCookie, "POST", "/api/x-import/inbox/import",
				sameOriginHeaders(phone), "{\"itemIds\":[" + i1 + "]}").status);

		JsonNode skippedPage = json.readTree(phoneGet(phone, phoneCookie, "/api/x-import/inbox/skipped").body());
		assertEquals(2, skippedPage.path("totalItems").asInt());

		RawResponse putBack = rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/unskip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + i2 + "]}");
		assertEquals(1, json.readTree(putBack.body).path("restoredCount").asInt());

		JsonNode pendingPage = json.readTree(phoneGet(phone, phoneCookie, "/api/x-import/inbox").body());
		assertEquals(1, pendingPage.path("totalItems").asInt());
		assertEquals(i2, pendingPage.path("items").get(0).path("id").asLong());
	}

	@Test
	void enforcesOriginChecksImmediateDisableFailClosedRestartAndRevocation() throws Exception {
		assumeLan();
		MockCdn cdn = startMockCdn(png(Color.GREEN, 64, 48));
		DesktopServer.Running running = startServer(cdn);
		String desktopCookie = openDesktopSession(running);
		String phoneCookie = pairPhone(running);
		URI phone = phoneBase(running);
		long itemId = seedLike(running, "sec-1", 10L, "sec", "photo", "https://pbs.twimg.com/media/Sec.png", 64, 48);

		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":true}");

		// Missing both Origin & Referer -> 403; Origin: null -> 403; evil Origin -> 403; cross-site Sec-Fetch-Site -> 403
		assertEquals(403, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				Map.of(), "{\"itemIds\":[" + itemId + "]}").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				Map.of("Origin", "null"), "{\"itemIds\":[" + itemId + "]}").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "POST", "/api/x-import/inbox/import",
				Map.of("Origin", "http://evil.example"), "{\"itemIds\":[" + itemId + "]}").status);
		assertEquals(403, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				Map.of("Origin", "http://" + phone.getHost() + ":" + phone.getPort(), "Sec-Fetch-Site", "cross-site"),
				"{\"itemIds\":[" + itemId + "]}").status);

		// Valid Referer alone (when Origin omitted) works, while cross-origin Referer fails
		assertEquals(403, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				Map.of("Referer", "http://evil.example/m/x.html"), "{\"itemIds\":[" + itemId + "]}").status);
		assertEquals(200, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/unskip",
				Map.of("Referer", "http://" + phone.getHost() + ":" + phone.getPort() + "/m/x.html", "Sec-Fetch-Site", "same-origin"),
				"{\"itemIds\":[" + itemId + "]}").status);

		// Disable permission mid-session -> immediate 403 on X inbox while gallery stays 200
		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":false}");
		assertEquals(403, phoneGet(phone, phoneCookie, "/api/x-import/inbox").statusCode());
		assertEquals(403, phoneGet(phone, phoneCookie, "/api/x-import/inbox/" + itemId + "/media/mk-1/content?size=small").statusCode());
		assertEquals(403, rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
				sameOriginHeaders(phone), "{\"itemIds\":[" + itemId + "]}").status);
		assertEquals(200, phoneGet(phone, phoneCookie, "/api/illustrations").statusCode());

		// Revoke device while permission is enabled -> immediate 403 MOBILE_FORBIDDEN
		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":true}");
		assertEquals(200, phoneGet(phone, phoneCookie, "/api/x-import/inbox").statusCode());
		String deviceId = running.context().getBean(MobileAccess.class).status().devices().get(0).id();
		assertEquals(204, desktopSend(running, desktopCookie, "DELETE", "/api/desktop/mobile/devices/" + deviceId, null).statusCode());
		HttpResponse<String> revoked = phoneGet(phone, phoneCookie, "/api/x-import/inbox");
		assertEquals(403, revoked.statusCode());
		assertTrue(revoked.body().contains("MOBILE_FORBIDDEN"), revoked.body());

		// Fail-closed disable when settings.properties is read-only on Windows
		Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
		Path settingsFile = home.resolve("config").resolve("settings.properties");
		Files.setAttribute(settingsFile, "dos:readonly", true);
		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":false}");
		assertFalse(running.context().getBean(MobileAccess.class).isXInboxEnabled());
		assertTrue(Files.exists(home.resolve(MobileAccess.X_INBOX_OFF_MARKER)));
		running.context().close();

		DesktopServer.Running restartedReadonly = startServer(cdn);
		assertFalse(restartedReadonly.context().getBean(MobileAccess.class).isXInboxEnabled());
		restartedReadonly.context().close();

		Files.setAttribute(settingsFile, "dos:readonly", false);
		DesktopServer.Running recovered = startServer(cdn);
		assertFalse(recovered.context().getBean(MobileAccess.class).isXInboxEnabled());
		assertFalse(Files.exists(home.resolve(MobileAccess.X_INBOX_OFF_MARKER)));
	}

	@Test
	void desktopAndPhoneConcurrentArchiveAndSkipNeverDoubleImportOrCorruptState() throws Exception {
		assumeLan();
		MockCdn cdn = startMockCdn(png(Color.BLUE, 64, 48));
		DesktopServer.Running running = startServer(cdn);
		String desktopCookie = openDesktopSession(running);
		String phoneCookie = pairPhone(running);
		URI phone = phoneBase(running);
		desktopSend(running, desktopCookie, "PUT", "/api/desktop/mobile/x-inbox", "{\"enabled\":true}");

		long archiveTarget = seedLike(running, "race-a", 20L, "raceA", "photo", "https://pbs.twimg.com/media/RaceA.png", 64, 48);
		long skipTarget = seedLike(running, "race-s", 10L, "raceS", "photo", "https://pbs.twimg.com/media/RaceS.png", 64, 48);

		var pool = Executors.newFixedThreadPool(4);
		try {
			CountDownLatch gate = new CountDownLatch(1);
			Callable<Integer> desktopArchive = () -> {
				gate.await();
				HttpResponse<String> r = desktopSend(running, desktopCookie, "POST", "/api/x-import/inbox/import",
						"{\"itemIds\":[" + archiveTarget + "]}");
				return json.readTree(r.body()).path("successCount").asInt();
			};
			Callable<Integer> phoneArchive = () -> {
				gate.await();
				RawResponse r = rawPhone(phone, phoneCookie, "POST", "/api/x-import/inbox/import",
						sameOriginHeaders(phone), "{\"itemIds\":[" + archiveTarget + "]}");
				return json.readTree(r.body).path("successCount").asInt();
			};
			Callable<Integer> desktopSkip = () -> {
				gate.await();
				HttpResponse<String> r = desktopSend(running, desktopCookie, "PATCH", "/api/x-import/inbox/skip",
						"{\"itemIds\":[" + skipTarget + "]}");
				return json.readTree(r.body()).path("skippedCount").asInt();
			};
			Callable<Integer> phoneSkip = () -> {
				gate.await();
				RawResponse r = rawPhone(phone, phoneCookie, "PATCH", "/api/x-import/inbox/skip",
						sameOriginHeaders(phone), "{\"itemIds\":[" + skipTarget + "]}");
				return json.readTree(r.body).path("skippedCount").asInt();
			};

			Future<Integer> da = pool.submit(desktopArchive);
			Future<Integer> pa = pool.submit(phoneArchive);
			Future<Integer> ds = pool.submit(desktopSkip);
			Future<Integer> ps = pool.submit(phoneSkip);
			gate.countDown();

			assertEquals(1, da.get() + pa.get(), "exactly one concurrent caller imports the post");
			assertEquals(1, ds.get() + ps.get(), "exactly one concurrent caller skips the post");
			assertEquals(1, count(running, "SELECT COUNT(*) FROM illustration"));
			assertEquals(1, count(running, "SELECT COUNT(*) FROM asset"));
			assertEquals("IMPORTED", running.context().getBean(JdbcTemplate.class)
					.queryForObject("SELECT status FROM x_like_item WHERE id = ?", String.class, archiveTarget));
			assertEquals("SKIPPED", running.context().getBean(JdbcTemplate.class)
					.queryForObject("SELECT status FROM x_like_item WHERE id = ?", String.class, skipTarget));
		} finally {
			pool.shutdownNow();
		}
	}

	private DesktopServer.Running startServer(MockCdn cdn) {
		Path settings = home.resolve("config").resolve("settings.properties");
		if (!Files.exists(settings)) {
			String addr = LanAddresses.siteLocal().get(0);
			try {
				Files.createDirectories(settings.getParent());
				Files.writeString(settings, "mobile.enabled=true\nmobile.address=" + addr + "\n", StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
		DesktopServer.Running running = DesktopServer.start(home, 0, true, Map.of(
				MobileAccess.LAN_PORT_PROPERTY, "0",
				"x.media.photo-cdn-base-url", cdn.baseUrl,
				"x.media.video-cdn-base-url", cdn.baseUrl));
		started.add(running);
		return running;
	}

	private String openDesktopSession(DesktopServer.Running running) throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(running.sessionUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(302, r.statusCode());
		String sc = r.headers().firstValue("Set-Cookie").orElseThrow();
		return sc.substring(0, sc.indexOf(';'));
	}

	private String pairPhone(DesktopServer.Running running) throws Exception {
		var pairing = running.context().getBean(MobileAccess.class).startPairing(null);
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(pairing.url())).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, r.statusCode());
		String sc = r.headers().firstValue("Set-Cookie").orElseThrow();
		return sc.substring(0, sc.indexOf(';'));
	}

	private static URI phoneBase(DesktopServer.Running running) {
		var status = running.context().getBean(MobileAccess.class).status();
		return URI.create("http://" + status.address() + ":" + status.port() + "/");
	}

	private long seedLike(DesktopServer.Running running, String postId, long order, String author, String mediaType,
			String sourceUrl, int width, int height) {
		XLikeRepository likes = running.context().getBean(XLikeRepository.class);
		XLikeMediaRepository media = running.context().getBean(XLikeMediaRepository.class);
		var m = new XLikeMedia("mk-1", 0, mediaType, sourceUrl, width, height);
		Long id = likes.insertIfAbsent(new XLikeCandidate(postId, "auth-" + author, author, "Display " + author,
				"post " + postId, Instant.now(), XLikeStatus.PENDING, List.of(m)), order);
		media.insert(id, m);
		return id;
	}

	private HttpResponse<String> desktopGet(DesktopServer.Running running, String cookie, String path) throws Exception {
		return desktopSend(running, cookie, "GET", path, null);
	}

	private HttpResponse<String> desktopSend(DesktopServer.Running running, String cookie, String method, String path,
			String body) throws Exception {
		var builder = HttpRequest.newBuilder(running.baseUri().resolve(path)).header("Cookie", cookie);
		if (body != null) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(body));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> phoneGet(URI phone, String cookie, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(phone.resolve(path)).header("Cookie", cookie).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<byte[]> phoneGetBytes(URI phone, String cookie, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(phone.resolve(path)).header("Cookie", cookie).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private static Map<String, String> sameOriginHeaders(URI phone) {
		return Map.of("Origin", "http://" + phone.getHost() + ":" + phone.getPort(), "Sec-Fetch-Site", "same-origin");
	}

	private static RawResponse rawPhone(URI phone, String cookie, String method, String path,
			Map<String, String> headers, String jsonBody) throws IOException {
		byte[] bodyBytes = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
		String host = phone.getHost() + ":" + phone.getPort();
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(phone.getHost(), phone.getPort()), 3000);
			socket.setSoTimeout(8000);
			OutputStream out = socket.getOutputStream();
			StringBuilder req = new StringBuilder();
			req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
			req.append("Host: ").append(host).append("\r\n");
			if (cookie != null) req.append("Cookie: ").append(cookie).append("\r\n");
			headers.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
			if (jsonBody != null) {
				req.append("Content-Type: application/json\r\n");
				req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
			}
			req.append("Connection: close\r\n\r\n");
			out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
			if (bodyBytes.length > 0) out.write(bodyBytes);
			out.flush();
			String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int split = raw.indexOf("\r\n\r\n");
			String head = split >= 0 ? raw.substring(0, split) : raw;
			String body = split >= 0 ? raw.substring(split + 4) : "";
			String firstLine = head.split("\r\n", 2)[0];
			int status = Integer.parseInt(firstLine.split(" ")[1]);
			return new RawResponse(status, head, body);
		}
	}

	private int count(DesktopServer.Running running, String sql) {
		return running.context().getBean(JdbcTemplate.class).queryForObject(sql, Integer.class);
	}

	private MockCdn startMockCdn(byte[] pngBytes) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		List<String> seen = new CopyOnWriteArrayList<>();
		AtomicInteger photoStatus = new AtomicInteger(200);
		server.createContext("/media/", exchange -> {
			String full = exchange.getRequestURI().getPath()
					+ (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
			seen.add(full);
			int status = photoStatus.get();
			if (status == 302) {
				exchange.getResponseHeaders().set("Location", "http://127.0.0.1/redirected");
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
				return;
			}
			if (status != 200) {
				byte[] err = ("err-" + status).getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/plain");
				exchange.sendResponseHeaders(status, err.length);
				try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
				return;
			}
			exchange.getResponseHeaders().set("Content-Type", "image/png");
			exchange.sendResponseHeaders(200, pngBytes.length);
			try (OutputStream os = exchange.getResponseBody()) { os.write(pngBytes); }
		});
		server.createContext("/tweet_video/", exchange -> {
			String full = exchange.getRequestURI().getPath();
			seen.add(full);
			byte[] mp4 = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
			exchange.getResponseHeaders().set("Content-Type", "video/mp4");
			exchange.sendResponseHeaders(200, mp4.length);
			try (OutputStream os = exchange.getResponseBody()) { os.write(mp4); }
		});
		server.start();
		servers.add(server);
		return new MockCdn("http://127.0.0.1:" + server.getAddress().getPort(), seen, photoStatus);
	}

	private static byte[] png(Color color, int w, int h) throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		var g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, w, h);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static void assumeLan() {
		Assumptions.assumeFalse(LanAddresses.siteLocal().isEmpty(), "a LAN address is needed for the phone connector");
	}

	private record MockCdn(String baseUrl, List<String> seen, AtomicInteger photoStatus) { }

	private record RawResponse(int status, String headers, String body) { }
}
