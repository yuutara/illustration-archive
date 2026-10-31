package com.yuutara.illustrationarchive.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.service.XPostPersistenceService;
import com.yuutara.illustrationarchive.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The X path of the desktop app on a real Spring context, Tomcat, file H2 and Flyway. X itself is a local fake:
 * every byte of OAuth, Likes and revocation goes over HTTP to it, so nothing here can reach or bill the real API.
 */
class DesktopXIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path home;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<DesktopServer.Running> started = new ArrayList<>();
	private final List<String> tokenForms = new CopyOnWriteArrayList<>();
	private final List<String> revocations = new CopyOnWriteArrayList<>();
	private final List<String> apiPaths = new CopyOnWriteArrayList<>();
	private final List<String> bearers = new CopyOnWriteArrayList<>();
	private volatile int tokenStatus = 200;
	private volatile int likesStatus = 200;
	private volatile String accessToken = "access-1";
	private HttpServer fakeX;
	private int callbackPort;

	@BeforeEach
	void startFakeX() throws IOException {
		fakeX = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		fakeX.createContext("/2/oauth2/token", this::token);
		fakeX.createContext("/2/oauth2/revoke", exchange -> {
			revocations.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			reply(exchange, 200, "{\"revoked\":true}");
		});
		fakeX.createContext("/2/users/me", exchange -> api(exchange, "{\"data\":{\"id\":\"42\",\"name\":\"me\",\"username\":\"me\"}}"));
		fakeX.createContext("/2/users/42/liked_tweets", exchange -> api(exchange, likesPage(exchange)));
		fakeX.start();
		try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			callbackPort = probe.getLocalPort();
		}
	}

	@AfterEach
	void stop() {
		started.forEach(running -> running.context().close());
		fakeX.stop(0);
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void connectingSyncsLikesIntoTheInboxAndArchivesAPostOnH2() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);

		JsonNode initial = json(send(running, cookie, "GET", "/api/x-auth/status", null));
		assertEquals("DISCONNECTED", initial.path("status").path("state").asText());
		assertEquals("http://127.0.0.1:" + callbackPort + "/x/callback", initial.path("callbackUrl").asText());
		assertEquals(100, initial.path("scanBudget").asInt());

		HttpResponse<String> unconnected = send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals(200, unconnected.statusCode(), unconnected.body());
		assertTrue(json(unconnected).path("partial").asBoolean());
		assertTrue(apiPaths.isEmpty(), "no X API request without a connected account");

		connect(running, cookie);
		assertEquals("CONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
		assertTrue(apiPaths.isEmpty(), "connecting must not read any billable endpoint");
		Properties saved = credentialFile();
		assertEquals("access-1", saved.getProperty("access_token"));
		assertEquals("refresh-1", saved.getProperty("refresh_token"));
		assertEquals("client-id-123", saved.getProperty("client_id"));

		HttpResponse<String> first = send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals(200, first.statusCode(), first.body());
		JsonNode report = json(first);
		assertFalse(report.path("partial").asBoolean(), first.body());
		assertEquals(3, report.path("recent").path("newCount").asInt(), first.body());
		assertEquals(1, report.path("recent").path("unsupportedCount").asInt(), first.body());

		JsonNode inbox = json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null));
		assertEquals(2, inbox.path("totalItems").asLong(), inbox.toString());
		assertEquals(List.of("1003", "1001"), inbox.path("items").valueStream().map(item -> item.path("xPostId").asText()).toList());
		JsonNode multiImage = inbox.path("items").get(0);
		assertEquals(2, multiImage.path("media").size());
		assertEquals("Artist Three", multiImage.path("authorDisplayName").asText());
		long skippedId = inbox.path("items").get(1).path("id").asLong();

		HttpResponse<String> second = send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals(200, second.statusCode(), second.body());
		assertEquals(0, json(second).path("recent").path("newCount").asInt(), "the same Likes are idempotent");

		HttpResponse<String> skip = send(running, cookie, "PATCH", "/api/x-import/inbox/skip", "{\"itemIds\":[" + skippedId + "]}");
		assertEquals(1, json(skip).path("skippedCount").asInt(), skip.body());
		assertEquals(1, json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("totalItems").asLong());

		long pendingId = multiImage.path("id").asLong();
		var context = running.context();
		XLikeRepository items = context.getBean(XLikeRepository.class);
		XLikeRepository.ImportItem item = items.findForImport(pendingId, false).orElseThrow();
		assertEquals(XLikeStatus.PENDING, item.status());
		FileStorage storage = context.getBean(FileStorage.class);
		var files = List.of(storage.store("a.png", new ByteArrayInputStream(png(40))),
				storage.store("b.png", new ByteArrayInputStream(png(41))));
		long illustrationId = context.getBean(XPostPersistenceService.class).persist(item, List.of(
				new XLikeMedia("m1", 0, "photo", "https://pbs.twimg.com/media/a.png", 40, 40),
				new XLikeMedia("m2", 1, "photo", "https://pbs.twimg.com/media/b.png", 41, 41)), files);

		assertEquals(0, json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("totalItems").asLong());
		JsonNode detail = json(send(running, cookie, "GET", "/api/illustrations/" + illustrationId, null));
		assertEquals(2, detail.path("assets").size(), detail.toString());
		assertEquals("Artist Three", detail.path("author").path("displayName").asText(), detail.toString());
		assertEquals("https://x.com/artist3/status/1003", detail.path("sourceUrl").asText());
		JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		assertEquals("IMPORTED", jdbc.queryForObject("SELECT status FROM x_like_item WHERE id = ?", String.class, pendingId));
	}

	@Test
	void syncRefreshesAnExpiredPublicClientTokenWithoutASecretAndKeepsItsProgressAcrossRestart() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		writeExpiredAccessToken();
		running.context().close();
		started.clear();

		accessToken = "access-2";
		DesktopServer.Running restarted = start();
		String newCookie = sessionCookie(restarted);
		HttpResponse<String> sync = send(restarted, newCookie, "POST", "/api/x-import/sync", "");
		assertFalse(json(sync).path("partial").asBoolean(), sync.body());

		String refresh = tokenForms.get(tokenForms.size() - 1);
		Map<String, String> form = form(refresh);
		assertEquals("refresh_token", form.get("grant_type"));
		assertEquals("client-id-123", form.get("client_id"));
		assertEquals("refresh-1", form.get("refresh_token"));
		assertFalse(form.containsKey("client_secret"));
		assertTrue(bearers.stream().allMatch("Bearer access-2"::equals), bearers.toString());
		assertEquals("refresh-2", credentialFile().getProperty("refresh_token"), "a rotated refresh token is persisted");
	}

	@Test
	void callbackRejectsAForgedStateWithoutEndingTheLoginAndDenialIsReported() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JsonNode waiting = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));
		assertEquals("WAITING", waiting.path("status").path("state").asText());
		Map<String, String> authorize = authorizeQuery(waiting);
		assertEquals("S256", authorize.get("code_challenge_method"));
		assertEquals("tweet.read users.read like.read offline.access", authorize.get("scope"));
		assertFalse(authorize.containsKey("client_secret"));

		HttpResponse<String> forged = http.send(HttpRequest.newBuilder(URI.create(callback() + "?state=forged&code=evil")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(400, forged.statusCode());
		assertEquals("WAITING", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
		assertTrue(tokenForms.isEmpty());

		HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create(callback()
				+ "?state=" + authorize.get("state") + "&error=access_denied")).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, denied.statusCode());
		JsonNode failed = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("FAILED", failed.path("state").asText());
		assertEquals("DENIED", failed.path("failure").asText());
		assertFalse(Files.exists(credentialPath()));
	}

	@Test
	void disconnectRemovesLocalTokensEvenWhenXCannotBeReached() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);

		JsonNode disconnected = json(send(running, cookie, "POST", "/api/x-auth/disconnect", ""));
		assertTrue(disconnected.path("revoked").asBoolean());
		assertFalse(Files.exists(credentialPath()));
		Map<String, String> revoked = form(revocations.get(0));
		assertEquals("refresh-1", revoked.get("token"));
		assertEquals("client-id-123", revoked.get("client_id"));
		assertEquals("DISCONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());

		connect(running, cookie);
		fakeX.stop(0);
		JsonNode offline = json(send(running, cookie, "POST", "/api/x-auth/disconnect", ""));
		assertFalse(offline.path("revoked").asBoolean());
		assertFalse(Files.exists(credentialPath()), "local tokens go first");
	}

	@Test
	void anUpstreamRejectionIsReportedWithoutLosingTheInbox() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		likesStatus = 429;
		JsonNode limited = json(send(running, cookie, "POST", "/api/x-import/sync", ""));
		assertTrue(limited.path("partial").asBoolean());
		assertEquals(429, limited.path("recent").path("upstreamStatus").asInt(), limited.toString());
		assertEquals(0, json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("totalItems").asLong());
	}

	@Test
	void geminiKeySavedInSettingsTakesEffectWithoutARestartAndIsNeverReturned() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		assertFalse(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiKeySaved").asBoolean());
		var keys = running.context().getBean(com.yuutara.illustrationarchive.service.GeminiKeyStore.class);
		assertEquals("", keys.apiKey());

		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"  test-key-1  \"}").statusCode());
		assertEquals("test-key-1", keys.apiKey());
		HttpResponse<String> settings = send(running, cookie, "GET", "/api/desktop/settings", null);
		assertTrue(json(settings).path("geminiKeySaved").asBoolean());
		assertFalse(settings.body().contains("test-key-1"));

		assertEquals(400, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"has space\"}").statusCode());
		assertEquals(204, send(running, cookie, "DELETE", "/api/desktop/settings/gemini-key", null).statusCode());
		assertEquals("", keys.apiKey());
	}

	@Test
	void settingsValidateProxyAndOnlyOpenAllowedExternalLinks() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		assertEquals(400, send(running, cookie, "PUT", "/api/desktop/settings/proxy", "{\"address\":\"not a proxy\"}").statusCode());
		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/proxy", "{\"address\":\"127.0.0.1:7897\"}").statusCode());
		assertEquals("127.0.0.1:7897", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("proxy").asText());
		var selector = (java.net.ProxySelector.getDefault());
		assertEquals(java.net.Proxy.Type.HTTP, selector.select(URI.create("https://api.x.com/2/users/me")).get(0).type());
		assertEquals(java.net.Proxy.NO_PROXY, selector.select(URI.create("http://127.0.0.1:47391/")).get(0));
		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/proxy", "{\"address\":\"\"}").statusCode());
		assertEquals(java.net.Proxy.NO_PROXY, selector.select(URI.create("https://api.x.com/")).get(0));

		assertEquals(400, send(running, cookie, "POST", "/api/desktop/open-external", "{\"url\":\"https://evil.example/\"}").statusCode());
		assertEquals(400, send(running, cookie, "POST", "/api/desktop/open-external", "{\"url\":\"file:///C:/Windows/notepad.exe\"}").statusCode());
		assertEquals(200, send(running, cookie, "POST", "/api/desktop/open-external", "{\"url\":\"https://x.com/i/status/1\"}").statusCode());
	}

	@Test
	void theCloseChoiceIsRememberedAcrossRestartsAndCanBeChangedBack() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		assertEquals("ASK", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("closeAction").asText(),
				"a first close must not hide the app without saying so");

		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/close-action", "{\"action\":\"TRAY\"}").statusCode());
		assertEquals("TRAY", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("closeAction").asText());
		DesktopSettings restarted = new DesktopSettings(running.context().getEnvironment().getProperty(DesktopServer.HOME_PROPERTY));
		assertEquals(CloseAction.TRAY, restarted.closeAction());

		assertEquals(400, send(running, cookie, "PUT", "/api/desktop/settings/close-action", "{\"action\":\"LATER\"}").statusCode());
		assertEquals(400, send(running, cookie, "PUT", "/api/desktop/settings/close-action", "{}").statusCode());
		assertEquals("TRAY", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("closeAction").asText());

		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/close-action", "{\"action\":\"ASK\"}").statusCode());
		assertEquals("ASK", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("closeAction").asText());
		assertEquals(403, send(running, "ia_desktop_session=wrong", "PUT", "/api/desktop/settings/close-action", "{\"action\":\"EXIT\"}").statusCode());
	}

	@Test
	void classDataTrainingVisitsEveryPageAndLeavesNothingBehind() throws Exception {
		DesktopClassDataTraining.run();
		try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
			assertEquals(0, files.filter(file -> file.getFileName().toString().startsWith("illustration-archive-training")).count());
		}
	}

	@Test
	void aSecondLaunchAsksTheRunningAppToShowItself() throws Exception {
		DesktopServer.Running running = start();
		java.util.concurrent.atomic.AtomicInteger shown = new java.util.concurrent.atomic.AtomicInteger();
		running.context().getBean(DesktopActivation.class).onShow(shown::incrementAndGet);

		assertEquals(204, send(running, sessionCookie(running), "POST", "/api/desktop/show", "").statusCode());
		assertEquals(1, shown.get());
		assertEquals(403, send(running, "ia_desktop_session=wrong", "POST", "/api/desktop/show", "").statusCode());
		assertEquals(1, shown.get(), "only the app itself may bring the window forward");
	}

	@Test
	void anUnauthenticatedRequestCannotReachTheSettings() throws Exception {
		DesktopServer.Running running = start();
		for (String path : List.of("/api/x-auth/status", "/api/desktop/settings")) {
			HttpResponse<String> rejected = http.send(HttpRequest.newBuilder(running.baseUri().resolve(path)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertEquals(403, rejected.statusCode(), path);
		}
		HttpRequest crossOrigin = HttpRequest.newBuilder(running.baseUri().resolve("/api/x-auth/start"))
				.header("Cookie", sessionCookie(running)).header("Origin", "http://localhost:3000")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"clientId\":\"client-id-123\"}")).build();
		assertEquals(403, http.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
	}

	private DesktopServer.Running start() {
		DesktopServer.Running running = DesktopServer.start(home, 0, true, Map.of(
				"x.api.base-url", "http://127.0.0.1:" + fakeX.getAddress().getPort(),
				"illustration-archive.desktop.x-callback-port", Integer.toString(callbackPort)));
		started.add(running);
		return running;
	}

	private void connect(DesktopServer.Running running, String cookie) throws Exception {
		JsonNode waiting = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));
		assertEquals("WAITING", waiting.path("status").path("state").asText(), waiting.toString());
		Map<String, String> authorize = authorizeQuery(waiting);
		HttpResponse<String> callback = http.send(HttpRequest.newBuilder(URI.create(callback()
				+ "?state=" + authorize.get("state") + "&code=the-code")).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, callback.statusCode(), callback.body());
		Map<String, String> exchange = form(tokenForms.get(tokenForms.size() - 1));
		assertEquals("authorization_code", exchange.get("grant_type"));
		assertEquals("the-code", exchange.get("code"));
		assertEquals("client-id-123", exchange.get("client_id"));
		assertEquals(callback(), exchange.get("redirect_uri"));
		assertEquals(authorize.get("code_challenge"), XAuthorizationService.challenge(exchange.get("code_verifier")));
	}

	private String callback() {
		return "http://127.0.0.1:" + callbackPort + "/x/callback";
	}

	private static Map<String, String> authorizeQuery(JsonNode connection) {
		return form(URI.create(connection.path("status").path("authorizeUrl").asText()).getRawQuery());
	}

	private static Map<String, String> form(String encoded) {
		Map<String, String> values = new HashMap<>();
		Arrays.stream(encoded.split("&")).map(pair -> pair.split("=", 2))
				.forEach(pair -> values.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
		return values;
	}

	private Path credentialPath() {
		return home.resolve("config").resolve("x-oauth-tokens.properties");
	}

	private Properties credentialFile() throws IOException {
		Properties values = new Properties();
		try (var reader = Files.newBufferedReader(credentialPath())) {
			values.load(reader);
		}
		return values;
	}

	private void writeExpiredAccessToken() throws IOException {
		Properties values = credentialFile();
		values.setProperty("expires_at", "2020-01-01T00:00:00Z");
		try (var writer = Files.newBufferedWriter(credentialPath())) {
			values.store(writer, null);
		}
	}

	private void token(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		tokenForms.add(body);
		if (exchange.getRequestHeaders().containsKey("Authorization")) {
			reply(exchange, 400, "{\"error\":\"public clients send no Authorization header\"}");
			return;
		}
		boolean refresh = form(body).get("grant_type").equals("refresh_token");
		reply(exchange, tokenStatus, "{\"token_type\":\"bearer\",\"expires_in\":7200,\"access_token\":\"" + accessToken
				+ "\",\"refresh_token\":\"" + (refresh ? "refresh-2" : "refresh-1") + "\"}");
	}

	private void api(HttpExchange exchange, String body) throws IOException {
		apiPaths.add(exchange.getRequestURI().toString());
		bearers.add(exchange.getRequestHeaders().getFirst("Authorization"));
		reply(exchange, exchange.getRequestURI().getPath().endsWith("liked_tweets") ? likesStatus : 200, body);
	}

	/** One page of four Likes: single photo, multi-photo, a text-only Post (Unsupported) and a repeat-free head. */
	private String likesPage(HttpExchange exchange) {
		String posts = IntStream.of(1003, 1002, 1001).mapToObj(id -> {
			String media = switch (id) {
				case 1003 -> ",\"attachments\":{\"media_keys\":[\"m1\",\"m2\"]}";
				case 1001 -> ",\"attachments\":{\"media_keys\":[\"m3\"]}";
				default -> "";
			};
			return "{\"id\":\"" + id + "\",\"author_id\":\"" + (id == 1003 ? "7" : "8") + "\",\"text\":\"post " + id
					+ "\",\"created_at\":\"2026-10-0" + (id - 1000) + "T00:00:00.000Z\"" + media + "}";
		}).collect(Collectors.joining(","));
		return "{\"data\":[" + posts + "],\"includes\":{\"users\":["
				+ "{\"id\":\"7\",\"name\":\"Artist Three\",\"username\":\"artist3\"},"
				+ "{\"id\":\"8\",\"name\":\"Artist One\",\"username\":\"artist1\"}],\"media\":["
				+ "{\"media_key\":\"m1\",\"type\":\"photo\",\"url\":\"https://pbs.twimg.com/media/a.png\",\"width\":40,\"height\":40},"
				+ "{\"media_key\":\"m2\",\"type\":\"photo\",\"url\":\"https://pbs.twimg.com/media/b.png\",\"width\":41,\"height\":41},"
				+ "{\"media_key\":\"m3\",\"type\":\"photo\",\"url\":\"https://pbs.twimg.com/media/c.jpg\",\"width\":42,\"height\":42}"
				+ "]},\"meta\":{\"result_count\":3}}";
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private String sessionCookie(DesktopServer.Running running) throws Exception {
		HttpResponse<String> entry = http.send(HttpRequest.newBuilder(running.sessionUri()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		String header = entry.headers().firstValue("set-cookie").orElseThrow();
		assertNotNull(header);
		return header.split(";", 2)[0];
	}

	private HttpResponse<String> send(DesktopServer.Running running, String cookie, String method, String path, String body)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(running.baseUri().resolve(path)).header("Cookie", cookie)
				.header("Accept", "application/json");
		if (body != null) {
			request.header("Content-Type", "application/json");
		}
		request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	private static byte[] png(int size) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", bytes);
		return bytes.toByteArray();
	}
}
