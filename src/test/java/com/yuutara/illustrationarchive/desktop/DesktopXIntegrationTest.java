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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
	private final List<String> geminiKeys = new CopyOnWriteArrayList<>();
	private final AtomicInteger tokenAnswers = new AtomicInteger();
	private volatile int geminiStatus = 200;
	private volatile int tokenStatus = 200;
	private volatile int likesStatus = 200;
	private volatile String accessToken = "access-1";
	private volatile String xUserId = "42";
	private volatile boolean pagedLikes;
	/** Authorization codes whose token response waits for the test to let it go. */
	private final Map<String, HeldExchange> heldCodes = new ConcurrentHashMap<>();
	private volatile HeldExchange heldUser;
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
		fakeX.createContext("/2/users/me", exchange -> {
			HeldExchange held = heldUser;
			if (held != null) held.block();
			api(exchange, "{\"data\":{\"id\":\"" + xUserId + "\",\"name\":\"me\",\"username\":\"user" + xUserId + "\"}}");
		});
		fakeX.createContext("/2/users/", exchange -> api(exchange, likesPage(exchange)));
		fakeX.createContext("/v1beta/models/", exchange -> {
			geminiKeys.add(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
			exchange.getRequestBody().readAllBytes();
			reply(exchange, geminiStatus, geminiStatus == 200 ? geminiAnswer()
					: "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"rejected\"}}");
		});
		// A held token response must not stop the fake from answering anything else.
		fakeX.setExecutor(Thread::startVirtualThread);
		fakeX.start();
		callbackPort = freePortOutsideTheEphemeralRange();
	}

	/** A released ephemeral port can go straight to Tomcat's port 0 or an outgoing connection, as the real one never does. */
	private static int freePortOutsideTheEphemeralRange() {
		for (;;) {
			int port = java.util.concurrent.ThreadLocalRandom.current().nextInt(20000, 32000);
			try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
				return probe.getLocalPort();
			} catch (IOException taken) {
				// Try another one.
			}
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
		assertEquals(50, initial.path("scanBudget").asInt(), "Settings offers 50 reads per sync until the user picks another");

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
		assertEquals(List.of(skippedId), json(skip).path("skippedIds").valueStream().map(JsonNode::asLong).toList());
		assertFalse(json(skip).path("skippedAt").asText().isEmpty(), skip.body());
		assertEquals(1, json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("totalItems").asLong());
		JsonNode skippedList = json(send(running, cookie, "GET", "/api/x-import/inbox/skipped?page=0&size=40", null));
		assertEquals(List.of(skippedId), skippedList.path("items").valueStream().map(i -> i.path("id").asLong()).toList());
		HttpResponse<String> putBack = send(running, cookie, "PATCH", "/api/x-import/inbox/unskip", "{\"itemIds\":[" + skippedId + "]}");
		assertEquals(1, json(putBack).path("restoredCount").asInt(), putBack.body());
		assertEquals(List.of("1003", "1001"), json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null))
				.path("items").valueStream().map(i -> i.path("xPostId").asText()).toList(), "back in its place");
		assertEquals(0, json(send(running, cookie, "GET", "/api/x-import/inbox/skipped", null)).path("totalItems").asLong());
		assertEquals(1, json(send(running, cookie, "PATCH", "/api/x-import/inbox/skip", "{\"itemIds\":[" + skippedId + "]}"))
				.path("skippedCount").asInt());

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
		assertEquals("post 1003", detail.path("note").asText(), "the Post's words are kept as the note");
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
	void anotherAccountNeverUsesTheSavedCursorAndTheOwnerCarriesOnAfterReconnecting() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		pagedLikes = true;
		connect(running, cookie);
		HttpResponse<String> first = send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals("ACTIVE", json(first).path("recentStatus").asText(), first.body());
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		String cursor = jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class);
		assertNotNull(cursor);
		long inboxBefore = json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("totalItems").asLong();

		xUserId = "43";
		connect(running, cookie);
		apiPaths.clear();
		HttpResponse<String> refused = send(running, cookie, "POST", "/api/x-import/sync", "");
		JsonNode report = json(refused);
		assertTrue(report.path("partial").asBoolean(), refused.body());
		assertEquals("ACCOUNT_MISMATCH", report.path("recent").path("stopReason").asText(), refused.body());
		assertEquals(0, report.path("recent").path("pagesRequested").asInt());
		assertEquals(List.of("/2/users/me"), apiPaths, "the only read is the one that says who this is");
		assertEquals(cursor, jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class));
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		JsonNode inbox = json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null));
		assertEquals(inboxBefore, inbox.path("totalItems").asLong(), "the Inbox stays as it was");
		assertEquals("CHANGED", inbox.path("syncProgress").path("accountStatus").asText(), "visible without paying to ask again");
		assertEquals("@user43", inbox.path("syncProgress").path("currentAccount").asText());
		assertEquals("43", inbox.path("syncProgress").path("currentAccountId").asText());
		assertEquals(400, answer(running, cookie, "adopt", inbox.path("syncProgress").path("accountConfirmation").asText()).statusCode(),
				"a recorded owner cannot be talked out of its progress");
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));

		xUserId = "42";
		connect(running, cookie);
		assertEquals("OK", json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null))
				.path("syncProgress").path("accountStatus").asText(), "new credentials make the sighting stale");
		apiPaths.clear();
		HttpResponse<String> resumed = send(running, cookie, "POST", "/api/x-import/sync", "");
		assertFalse(json(resumed).path("recent").path("stopReason").asText().equals("ACCOUNT_MISMATCH"), resumed.body());
		assertTrue(apiPaths.stream().anyMatch(path -> path.startsWith("/2/users/42/liked_tweets") && path.contains("pagination_token=" + cursor)),
				apiPaths.toString());
		assertTrue(apiPaths.stream().noneMatch(path -> path.contains("/2/users/43/")), apiPaths.toString());
	}

	@Test
	void reconnectingTheSameAccountUnderAnotherClientIdKeepsItsProgress() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		pagedLikes = true;
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String cursor = running.context().getBean(JdbcTemplate.class)
				.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class);

		assertTrue(json(send(running, cookie, "POST", "/api/x-auth/disconnect", "")).path("revoked").asBoolean());
		JsonNode waiting = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"another-client-456\"}"));
		http.send(HttpRequest.newBuilder(URI.create(callback() + "?state=" + authorizeQuery(waiting).get("state") + "&code=the-code"))
				.GET().build(), HttpResponse.BodyHandlers.ofString());
		apiPaths.clear();
		send(running, cookie, "POST", "/api/x-import/sync", "");

		assertTrue(apiPaths.stream().anyMatch(path -> path.contains("pagination_token=" + cursor)), apiPaths.toString());
	}

	@Test
	void progressWithNoRecordedOwnerWaitsForTheUserInsteadOfJoiningTheNewLogin() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'old-history', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at, matched_count, order_next, order_floor, coverage_to_end)"
				+ " VALUES (2, 'old-recent', 25, 'ACTIVE', '2026-09-03 01:02:03', 0, 100000, 0, FALSE)");
		connect(running, cookie);
		assertEquals("OK", json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null))
				.path("syncProgress").path("accountStatus").asText(), "signing in reads nothing, so nobody knows yet");

		HttpResponse<String> refused = send(running, cookie, "POST", "/api/x-import/sync", "");

		assertEquals("ACCOUNT_MISMATCH", json(refused).path("recent").path("stopReason").asText(), refused.body());
		assertEquals(List.of("/2/users/me"), apiPaths);
		JsonNode progress = json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("syncProgress");
		assertEquals("UNATTRIBUTED", progress.path("accountStatus").asText());
		assertEquals("@user42", progress.path("currentAccount").asText(), "the account has to be recognisable to be confirmed");
		assertEquals(null, jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		assertEquals("old-recent", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class));
		assertEquals("old-history", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 1", String.class));

		apiPaths.clear();
		String confirmation = progress.path("accountConfirmation").asText();
		assertEquals(204, answer(running, cookie, "adopt", confirmation).statusCode());
		assertEquals(409, answer(running, cookie, "adopt", confirmation).statusCode(), "a repeat answers a question that is gone");
		assertTrue(apiPaths.isEmpty(), "confirming reads nothing from X");
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		assertEquals("old-recent", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class));

		pagedLikes = true;
		send(running, cookie, "POST", "/api/x-import/sync", "");
		assertTrue(apiPaths.stream().anyMatch(path -> path.startsWith("/2/users/42/liked_tweets") && path.contains("pagination_token=old-recent")),
				"the confirmed progress carries on: " + apiPaths);
	}

	@Test
	void unattributedProgressCanInsteadBeDroppedWhenTheUserSaysItIsNotThisAccounts() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		seedItem(jdbc);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'old-history', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		jdbc.update("INSERT INTO x_like_sync_seen_token (sync_id, token_sha256) VALUES (1, ?)", (Object) new byte[32]);
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");

		String confirmation = syncProgress(running, cookie).path("accountConfirmation").asText();
		apiPaths.clear();
		assertEquals(204, answer(running, cookie, "restart", confirmation).statusCode());

		assertTrue(apiPaths.isEmpty(), "starting over reads nothing, and no sync starts by itself");
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_state", Long.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_seen_token", Long.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item WHERE x_post_id = '900'", Long.class), "the Inbox stays");
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		assertEquals("OK", json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null))
				.path("syncProgress").path("accountStatus").asText());
	}

	@Test
	void changingAccountStartsOverOnlyOnceAnotherAccountWasActuallySeen() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		pagedLikes = true;
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		long kept = jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item", Long.class);
		assertTrue(syncProgress(running, cookie).path("accountConfirmation").isNull(), "nothing to answer, so nothing to send back");
		assertEquals(409, answer(running, cookie, "restart", "42/43/2026-09-03T01:02:03").statusCode(),
				"nothing was detected, so nothing may be dropped");
		assertEquals(400, send(running, cookie, "POST", "/api/x-import/sync/account/restart", "").statusCode(), "an answer has to say what it answers");
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_state WHERE id = 2", Long.class));

		xUserId = "43";
		connect(running, cookie);
		assertTrue(syncProgress(running, cookie).path("accountConfirmation").isNull(),
				"signing in alone proves nothing: only a sync says who this is");
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String confirmation = syncProgress(running, cookie).path("accountConfirmation").asText();
		assertEquals(204, answer(running, cookie, "restart", confirmation).statusCode());
		assertEquals(409, answer(running, cookie, "restart", confirmation).statusCode(), "a repeat finds nothing to do");

		apiPaths.clear();
		HttpResponse<String> fresh = send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals(kept, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item", Long.class), "the same fake Posts are not filed twice");
		assertEquals("ACTIVE", json(fresh).path("recentStatus").asText(), fresh.body());
		assertTrue(apiPaths.stream().anyMatch(path -> path.startsWith("/2/users/43/liked_tweets") && !path.contains("pagination_token")), apiPaths.toString());
		assertTrue(apiPaths.stream().noneMatch(path -> path.contains("/2/users/42/")), apiPaths.toString());
		assertEquals("43", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
	}

	@Test
	void startingOverIsAllOrNothing() throws Exception {
		DesktopServer.Running running = start();
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		var repository = running.context().getBean(com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository.class);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'kept', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		jdbc.update("INSERT INTO x_like_sync_seen_token (sync_id, token_sha256) VALUES (1, ?)", (Object) new byte[32]);
		repository.bindAccount("42");
		repository.observeAccount("43", "user43");
		jdbc.execute("ALTER TABLE x_like_sync_seen_token RENAME TO x_like_sync_seen_token_away");

		assertThrows(RuntimeException.class, () -> repository.restartFor(repository.loadAccount().orElseThrow()),
				"the last step fails after the owner and the progress were already written");

		assertEquals("kept", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 1", String.class));
		assertEquals("42", repository.loadAccount().orElseThrow().ownerId());
		assertEquals("43", repository.loadAccount().orElseThrow().observedId());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_seen_token_away", Long.class));
	}

	@Test
	void aConfirmationForOneAccountIsNotAppliedToTheAccountThatReplacedIt() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'old-history', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String shownFor42 = syncProgress(running, cookie).path("accountConfirmation").asText();

		xUserId = "43";
		connect(running, cookie);
		assertEquals(409, answer(running, cookie, "adopt", shownFor42).statusCode(), "reconnecting already ended that question");
		send(running, cookie, "POST", "/api/x-import/sync", "");
		JsonNode nowShown = syncProgress(running, cookie);
		assertEquals("@user43", nowShown.path("currentAccount").asText());

		HttpResponse<String> stale = answer(running, cookie, "adopt", shownFor42);

		assertEquals(409, stale.statusCode(), stale.body());
		assertEquals("CONFIRMATION_EXPIRED", json(stale).path("code").asText());
		assertEquals(null, jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		assertEquals("old-history", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 1", String.class));
		assertEquals(409, answer(running, cookie, "restart", shownFor42).statusCode(), "starting over is bound the same way");
		assertEquals("old-history", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 1", String.class));
		assertEquals("UNATTRIBUTED", syncProgress(running, cookie).path("accountStatus").asText(), "the page reads this and asks again");

		assertEquals(204, answer(running, cookie, "adopt", nowShown.path("accountConfirmation").asText()).statusCode());
		assertEquals("43", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
	}

	@Test
	void startingOverForOneAccountIsNotAppliedToTheAccountThatReplacedIt() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		pagedLikes = true;
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		String cursor = jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class);
		xUserId = "43";
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String shownFor43 = syncProgress(running, cookie).path("accountConfirmation").asText();

		xUserId = "44";
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals(409, answer(running, cookie, "restart", shownFor43).statusCode());

		assertEquals(cursor, jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 2", String.class));
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		assertEquals("44", jdbc.queryForObject("SELECT observed_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
	}

	@Test
	void theSavedStateIsComparedAtTheMomentOfWritingNotJustBeforeIt() throws Exception {
		DesktopServer.Running running = start();
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		var repository = running.context().getBean(com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository.class);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'kept', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		repository.observeAccount("43", "user43");
		var seen = repository.loadAccount().orElseThrow();

		// Reconnecting does exactly this, and it does not wait for the sync lock.
		repository.forgetObservedAccount();

		assertFalse(repository.settleAccount(seen));
		assertFalse(repository.restartFor(seen));
		assertEquals("kept", jdbc.queryForObject("SELECT next_token FROM x_like_sync_state WHERE id = 1", String.class));
		assertEquals(null, repository.loadAccount().orElseThrow().ownerId());
	}

	@Test
	void twoWindowsAnsweringAtOnceCannotBothSucceed() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'kept', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String confirmation = syncProgress(running, cookie).path("accountConfirmation").asText();

		var adopt = CompletableFuture.supplyAsync(() -> statusOf(running, cookie, "adopt", confirmation));
		var restart = CompletableFuture.supplyAsync(() -> statusOf(running, cookie, "restart", confirmation));

		assertEquals(List.of(204, 409), List.of(adopt.join(), restart.join()).stream().sorted().toList());
		assertEquals("42", jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
	}

	@Test
	void anAnswerGivenDuringASyncWaitsForItAndIsJudgedByWhatTheSyncFound() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, 'old-history', 25, 'ACTIVE', '2026-09-03 01:02:03')");
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String shownFor42 = syncProgress(running, cookie).path("accountConfirmation").asText();

		xUserId = "43";
		heldUser = new HeldExchange();
		var sync = CompletableFuture.supplyAsync(() -> {
			try {
				return send(running, cookie, "POST", "/api/x-import/sync", "");
			} catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		});
		heldUser.awaitArrival();
		var answered = CompletableFuture.supplyAsync(() -> statusOf(running, cookie, "adopt", shownFor42));
		Thread.sleep(300);
		assertFalse(answered.isDone(), "a sync that is still finding out who this is decides first");

		heldUser.release();
		sync.join();
		assertEquals(409, answered.join());
		assertEquals(null, jdbc.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
		assertEquals("43", jdbc.queryForObject("SELECT observed_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
	}

	@Test
	void theFirstSyncOnAnEmptyLibraryBindsItsAccount() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals("42", running.context().getBean(JdbcTemplate.class)
				.queryForObject("SELECT owner_x_user_id FROM x_like_sync_account WHERE id = 1", String.class));
	}

	@Test
	void continuingHistoryDirectlyIsRefusedForAnotherAccountToo() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO x_like_sync_account (id, owner_x_user_id, updated_at) VALUES (1, '42', '2026-09-03 01:02:03')");
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, NULL, 25, 'COMPLETE', '2026-09-03 01:02:03')");
		xUserId = "43";

		HttpResponse<String> refused = send(running, cookie, "POST", "/api/x-import/sync/continuation?maxResults=25&maxPages=1", "");

		assertEquals(502, refused.statusCode(), "another account's completion mark is not an answer: " + refused.body());
		assertEquals(List.of("/2/users/me"), apiPaths);
	}

	@Test
	void cancellingDuringTheExchangeEndsTheLoginAndItsLateTokensAreRevokedUnsaved() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		Map<String, String> authorize = authorizeQuery(json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}")));
		HeldExchange held = hold("slow");
		var callback = callbackAsync(authorize.get("state"), "slow");
		held.awaitArrival();
		JsonNode exchanging = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("WAITING", exchanging.path("state").asText());
		assertTrue(exchanging.path("authorizeUrl").isNull(), "nothing left to copy: the browser has already been back");

		JsonNode cancelled = json(send(running, cookie, "POST", "/api/x-auth/cancel", ""));
		assertEquals("DISCONNECTED", cancelled.path("status").path("state").asText(), "cancelling does not wait for X");

		held.release();
		callback.join();
		awaitRevocation("refresh-slow");
		assertFalse(Files.exists(credentialPath()), "a late answer is not a login");
		assertEquals("DISCONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
	}

	@Test
	void cancellingAReconnectLeavesTheConnectedAccountAlone() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		Map<String, String> authorize = authorizeQuery(json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}")));
		HeldExchange held = hold("slow");
		var callback = callbackAsync(authorize.get("state"), "slow");
		held.awaitArrival();

		assertEquals("CONNECTED", json(send(running, cookie, "POST", "/api/x-auth/cancel", "")).path("status").path("state").asText());
		held.release();
		callback.join();
		awaitRevocation("refresh-slow");

		assertEquals("refresh-1", credentialFile().getProperty("refresh_token"));
		assertEquals("CONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
	}

	@Test
	void anOlderAttemptFinishingLateCannotReplaceANewerLogin() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		Map<String, String> older = authorizeQuery(json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}")));
		HeldExchange held = hold("older");
		var olderCallback = callbackAsync(older.get("state"), "older");
		held.awaitArrival();

		JsonNode restarted = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));
		assertEquals("WAITING", restarted.path("status").path("state").asText(), "the port is free again: " + restarted);
		Map<String, String> newer = authorizeQuery(restarted);
		assertEquals(200, callbackAsync(newer.get("state"), "newer").join());
		assertEquals("refresh-newer", credentialFile().getProperty("refresh_token"));

		held.release();
		olderCallback.join();
		awaitRevocation("refresh-older");
		assertEquals("refresh-newer", credentialFile().getProperty("refresh_token"));
		assertEquals("CONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
	}

	@Test
	void anOlderAttemptFailingLateDoesNotDisturbTheOneNowWaiting() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		Map<String, String> older = authorizeQuery(json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}")));
		HeldExchange held = hold("older");
		var olderCallback = callbackAsync(older.get("state"), "older");
		held.awaitArrival();
		JsonNode restarted = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));

		tokenStatus = 400;
		held.release();
		olderCallback.join();
		awaitTokenAnswers(1);

		JsonNode status = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("WAITING", status.path("state").asText(), status.toString());
		assertEquals(restarted.path("status").path("authorizeUrl").asText(), status.path("authorizeUrl").asText());
	}

	@Test
	void disconnectingDuringTheExchangeLeavesNoLoginBehind() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		Map<String, String> authorize = authorizeQuery(json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}")));
		HeldExchange held = hold("slow");
		var callback = callbackAsync(authorize.get("state"), "slow");
		held.awaitArrival();

		send(running, cookie, "POST", "/api/x-auth/disconnect", "");
		held.release();
		callback.join();
		awaitRevocation("refresh-slow");

		assertFalse(Files.exists(credentialPath()));
		assertEquals("DISCONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
	}

	@Test
	void theAttemptLifetimeLimitsWaitingForTheCallbackNotAnExchangeAlreadyUnderWay() throws Exception {
		DesktopServer.Running running = start(Map.of("illustration-archive.desktop.x-authorization-seconds", "1"));
		String cookie = sessionCookie(running);
		json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));
		Thread.sleep(1500);
		JsonNode expired = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("FAILED", expired.path("state").asText(), expired.toString());
		assertEquals("TIMED_OUT", expired.path("failure").asText());
		assertFalse(Files.exists(credentialPath()));

		Map<String, String> authorize = authorizeQuery(json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}")));
		HeldExchange held = hold("slow");
		var callback = callbackAsync(authorize.get("state"), "slow");
		held.awaitArrival();
		Thread.sleep(1500);
		assertEquals("WAITING", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());

		held.release();
		assertEquals(200, callback.join());
		assertEquals("CONNECTED", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("state").asText());
		assertEquals("refresh-slow", credentialFile().getProperty("refresh_token"));
	}

	@Test
	void invalidProgressIsOfferedBeforeAnySyncAndResetKeepsTheInboxWithoutReadingX() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		JdbcTemplate jdbc = running.context().getBean(JdbcTemplate.class);
		long kept = jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item", Long.class);
		assertTrue(kept > 0);

		jdbc.update("UPDATE x_like_sync_state SET status = 'INVALID' WHERE id = 2");
		assertEquals(400, send(running, cookie, "POST", "/api/x-import/sync/continuation/reset", "").statusCode(),
				"History is not what failed, so its reset refuses");
		JsonNode offered = json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("syncProgress");
		assertEquals("INVALID", offered.path("recentStatus").asText(), offered.toString());
		apiPaths.clear();

		assertEquals(204, send(running, cookie, "POST", "/api/x-import/sync/recent/reset", "").statusCode());
		assertEquals(400, send(running, cookie, "POST", "/api/x-import/sync/recent/reset", "").statusCode(), "a repeat finds nothing to reset");
		assertTrue(apiPaths.isEmpty(), "resetting reads nothing from X");
		assertEquals(kept, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item", Long.class));
		assertEquals("ACTIVE", json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null))
				.path("syncProgress").path("recentStatus").asText(), "nothing resynced by itself");

		jdbc.update("DELETE FROM x_like_sync_state WHERE id = 1");
		jdbc.update("INSERT INTO x_like_sync_state (id, next_token, max_results, status, updated_at) VALUES (1, NULL, 25, 'INVALID', '2026-09-03 01:02:03')");
		jdbc.update("UPDATE x_like_sync_state SET status = 'INVALID' WHERE id = 2");
		assertEquals(204, send(running, cookie, "POST", "/api/x-import/sync/continuation/reset", "").statusCode());
		JsonNode progress = json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("syncProgress");
		assertEquals("ACTIVE", progress.path("recentStatus").asText(), "the History reset covers Recent as well");
		assertEquals("UNKNOWN", progress.path("historyStatus").asText());
		assertEquals(kept, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item", Long.class));
		assertTrue(apiPaths.isEmpty());
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
	void settingsShowTheVersionThePackagedAppWasBuiltWith() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		assertEquals("开发版", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("version").asText());

		System.setProperty("jpackage.app-version", "9.8.7");
		try {
			assertEquals("9.8.7", json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("version").asText());
		} finally {
			System.clearProperty("jpackage.app-version");
		}
	}

	@Test
	void aLibraryWithNoXOrAiConfigurationWorksAndNeverReachesOut() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);

		long id = importImage(running, cookie);
		assertEquals(204, send(running, cookie, "PATCH", "/api/illustrations/" + id, "{\"title\":\"edited\"}").statusCode());
		assertEquals("edited", json(send(running, cookie, "GET", "/api/illustrations/" + id, null)).path("title").asText());
		assertEquals(1, json(send(running, cookie, "GET", "/api/illustrations?page=0&size=48&q=edited", null)).path("totalElements").asLong());

		JsonNode settings = json(send(running, cookie, "GET", "/api/desktop/settings", null));
		assertFalse(settings.path("geminiKeySaved").asBoolean());
		assertTrue(settings.path("geminiVerifiedAt").isNull());
		JsonNode connection = json(send(running, cookie, "GET", "/api/x-auth/status", null));
		assertEquals("NONE", connection.path("status").path("savedLogin").asText());
		assertEquals("", connection.path("clientId").asText());
		assertFalse(Files.exists(home.resolve("config")), "nothing to configure, so nothing is written");
		assertTrue(tokenForms.isEmpty() && apiPaths.isEmpty() && geminiKeys.isEmpty());

		HttpResponse<String> noKey = send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "");
		assertEquals(503, noKey.statusCode(), noKey.body());
		assertTrue(geminiKeys.isEmpty(), "no key, no upload");
	}

	@Test
	void aReconnectInProgressOrFailedLeavesTheExistingLoginUsable() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);

		JsonNode waiting = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));
		assertEquals("WAITING", waiting.path("status").path("state").asText());
		assertEquals("USABLE", waiting.path("status").path("savedLogin").asText(), "X 收藏 keeps its Sync button meanwhile");
		assertFalse(waiting.path("status").path("browserOpened").asBoolean(), "the page must not claim a browser opened");

		http.send(HttpRequest.newBuilder(URI.create(callback() + "?state=" + authorizeQuery(waiting).get("state") + "&error=access_denied"))
				.GET().build(), HttpResponse.BodyHandlers.ofString());
		JsonNode failed = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("FAILED", failed.path("state").asText());
		assertEquals("USABLE", failed.path("savedLogin").asText());
		assertEquals("refresh-1", credentialFile().getProperty("refresh_token"));
		assertFalse(json(send(running, cookie, "POST", "/api/x-import/sync", "")).path("partial").asBoolean());
	}

	@Test
	void tokensCopiedFromTheMySqlSetupAreNotCalledConnectedAndRemovingThemNeverContactsX() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		Files.createDirectories(credentialPath().getParent());
		Files.writeString(credentialPath(), "access_token=old-access\nrefresh_token=old-refresh\nexpires_at=2030-01-01T00:00:00Z\n");

		JsonNode legacy = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("DISCONNECTED", legacy.path("state").asText(), "the app has no Client Secret to refresh these with");
		assertEquals("UNUSABLE", legacy.path("savedLogin").asText());

		Files.writeString(credentialPath(), "access_token=only-half-a-pair\n");
		assertEquals("UNUSABLE", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("savedLogin").asText());

		send(running, cookie, "POST", "/api/x-auth/disconnect", "");
		assertFalse(Files.exists(credentialPath()));
		assertEquals("NONE", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status").path("savedLogin").asText());
		assertTrue(revocations.isEmpty() && tokenForms.isEmpty() && apiPaths.isEmpty(), "these tokens are not this app's to revoke");
	}

	@Test
	void aClientIdThatCannotBeSavedSaysSoAndSavingAgainWorks() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		Files.writeString(home.resolve("config"), "a file where the folder should be");

		HttpResponse<String> refused = send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}");
		assertEquals(500, refused.statusCode(), refused.body());
		assertTrue(json(refused).path("message").asText().contains("写入数据文件夹失败"), refused.body());
		JsonNode unchanged = json(send(running, cookie, "GET", "/api/x-auth/status", null));
		assertEquals("DISCONNECTED", unchanged.path("status").path("state").asText());
		assertEquals("", unchanged.path("clientId").asText(), "what is shown is what is on disk");

		Files.delete(home.resolve("config"));
		assertEquals("WAITING", json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"))
				.path("status").path("state").asText());
		assertEquals("client-id-123", new DesktopSettings(home.toString()).xClientId(), "a restart reads the saved value");
	}

	@Test
	void tokensThatCannotBeSavedAreRevokedAndReportedAsThat() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JsonNode waiting = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));
		Files.createDirectories(credentialPath().resolve("in-the-way"));

		http.send(HttpRequest.newBuilder(URI.create(callback() + "?state=" + authorizeQuery(waiting).get("state") + "&code=the-code"))
				.GET().build(), HttpResponse.BodyHandlers.ofString());

		JsonNode failed = json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("status");
		assertEquals("FAILED", failed.path("state").asText(), failed.toString());
		assertEquals("SAVE_FAILED", failed.path("failure").asText(), "this is not a network problem");
		awaitRevocation("refresh-1");
	}

	@Test
	void onlyAFinishedSyncOnTheCurrentLoginCountsAsVerifiedAndNamesTheAccount() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		JsonNode authorized = json(send(running, cookie, "GET", "/api/x-auth/status", null));
		assertEquals("CONNECTED", authorized.path("status").path("state").asText());
		assertTrue(authorized.path("verifiedAt").isNull() && authorized.path("verifiedAccount").isNull(), "no account before X was read");
		assertTrue(apiPaths.isEmpty(), "authorizing neither syncs nor asks who this is");

		likesStatus = 429;
		send(running, cookie, "POST", "/api/x-import/sync", "");
		assertTrue(json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAt").isNull(), "a failed sync proves nothing");

		likesStatus = 200;
		send(running, cookie, "POST", "/api/x-import/sync", "");
		JsonNode verified = json(send(running, cookie, "GET", "/api/x-auth/status", null));
		assertFalse(verified.path("verifiedAt").isNull(), verified.toString());
		assertEquals("user42", verified.path("verifiedAccount").asText());
		assertEquals("user42", new DesktopSettings(home.toString()).xVerifiedAccount(), "a restart still knows");

		DesktopEvidence evidence = running.context().getBean(DesktopEvidence.class);
		String oldLogin = evidence.xAttempt();
		connect(running, cookie);
		assertTrue(json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").isNull(),
				"a new login may be another account");
		evidence.xWorked(oldLogin, "user42");
		assertTrue(json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAt").isNull(),
				"a sync that began on the old login cannot vouch for the new one");

		send(running, cookie, "POST", "/api/x-import/sync", "");
		evidence.xWorked(oldLogin, "late-account");
		assertEquals("user42", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").asText(),
				"nor can it replace what the new login has proved since");
	}

	@Test
	void savingAKeyProvesNothingAndOnlyAnAnalysisTheUserAskedForDoes() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		long id = importImage(running, cookie);
		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-1\"}").statusCode());
		JsonNode saved = json(send(running, cookie, "GET", "/api/desktop/settings", null));
		assertTrue(saved.path("geminiKeySaved").asBoolean());
		assertTrue(saved.path("geminiVerifiedAt").isNull());
		assertTrue(geminiKeys.isEmpty(), "saving a key must not call Gemini");

		geminiStatus = 400;
		HttpResponse<String> rejected = send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "");
		assertEquals(502, rejected.statusCode(), rejected.body());
		assertTrue(json(rejected).path("message").asText().contains("到设置检查"), rejected.body());
		assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull());

		geminiStatus = 200;
		HttpResponse<String> analyzed = send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "");
		assertEquals(200, analyzed.statusCode(), analyzed.body());
		assertEquals(List.of("test-key-1", "test-key-1"), geminiKeys);
		assertFalse(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull());
		assertNotNull(new DesktopSettings(home.toString()).aiVerifiedAt(), "a restart still knows");

		DesktopEvidence evidence = running.context().getBean(DesktopEvidence.class);
		String oldKey = evidence.aiAttempt();
		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-2\"}").statusCode());
		evidence.aiWorked(oldKey);
		assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull(),
				"the proof belonged to the key that was replaced");

		send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "");
		evidence.aiWorked(oldKey);
		assertFalse(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull(),
				"a late result about the old key cannot replace what the new key has proved");
		assertEquals(204, send(running, cookie, "DELETE", "/api/desktop/settings/gemini-key", null).statusCode());
		assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull());
	}

	@Test
	void aNewKeyInheritsNoProofWhileTheSettingsFileCannotBeWrittenNorAfterARestart() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		long id = importImage(running, cookie);
		send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-1\"}");
		assertEquals(200, send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "").statusCode());
		assertFalse(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull());

		try (Unwritable settings = settingsCannotBeReplaced()) {
			assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-2\"}").statusCode(),
					"the key itself saved fine, so saying otherwise would be wrong");
			assertEquals("test-key-2", Files.readString(home.resolve("config").resolve("gemini-key.txt")));
			assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull(),
					"the proof belonged to the key that was replaced");

			assertEquals(200, send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "").statusCode(),
					"failing to record a success must not undo the success");
			assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull(),
					"nothing was recorded, so nothing is claimed");
		}
		assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull());
		assertTrue(Files.readString(settingsFile()).contains("ai.verified-at"), "the old record is on disk, which is the point of this test");

		running.context().close();
		started.clear();
		DesktopServer.Running restarted = start();
		String newCookie = sessionCookie(restarted);
		assertTrue(json(send(restarted, newCookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull(),
				"a restart reads the old record and must still not believe it");

		assertEquals(200, send(restarted, newCookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "").statusCode());
		assertFalse(json(send(restarted, newCookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull(),
				"with the file writable again, the next real success counts");
	}

	@Test
	void aNewLoginInheritsNoProofWhileTheSettingsFileCannotBeWrittenNorAfterARestart() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		assertEquals("user42", json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").asText());
		JsonNode waiting = json(send(running, cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"client-id-123\"}"));

		try (Unwritable settings = settingsCannotBeReplaced()) {
			HttpResponse<String> callback = http.send(HttpRequest.newBuilder(URI.create(callback() + "?state="
					+ authorizeQuery(waiting).get("state") + "&code=second")).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, callback.statusCode(), "the new tokens saved fine");
			assertEquals("refresh-second", credentialFile().getProperty("refresh_token"));
			JsonNode reconnected = json(send(running, cookie, "GET", "/api/x-auth/status", null));
			assertEquals("CONNECTED", reconnected.path("status").path("state").asText(), reconnected.toString());
			assertTrue(reconnected.path("verifiedAt").isNull() && reconnected.path("verifiedAccount").isNull(),
					"a new login may be another account: " + reconnected);

			assertFalse(json(send(running, cookie, "POST", "/api/x-import/sync", "")).path("partial").asBoolean(),
					"failing to record a success must not undo the success");
			assertTrue(json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAt").isNull());
		}
		assertTrue(json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").isNull());
		assertTrue(Files.readString(settingsFile()).contains("x.verified-account"), "the old record is on disk, which is the point of this test");

		running.context().close();
		started.clear();
		DesktopServer.Running restarted = start();
		String newCookie = sessionCookie(restarted);
		assertTrue(json(send(restarted, newCookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").isNull(),
				"a restart reads the old record and must still not believe it");

		send(restarted, newCookie, "POST", "/api/x-import/sync", "");
		assertEquals("user42", json(send(restarted, newCookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").asText(),
				"with the file writable again, the next real success counts");
	}

	@Test
	void aRefreshIsTheSameLoginSoItsProofSurvivesRotatedTokensAndARestart() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		String login = credentialFile().getProperty("login_id");
		assertNotNull(login);
		writeExpiredAccessToken();
		running.context().close();
		started.clear();

		DesktopServer.Running restarted = start();
		String newCookie = sessionCookie(restarted);
		assertFalse(json(send(restarted, newCookie, "POST", "/api/x-import/sync", "")).path("partial").asBoolean());

		assertEquals("refresh-2", credentialFile().getProperty("refresh_token"), "the refresh really happened");
		assertEquals(login, credentialFile().getProperty("login_id"));
		assertEquals("user42", json(send(restarted, newCookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").asText());
	}

	@Test
	void anUnwritableSettingsFileDoesNotStopCredentialsBeingRemovedAndShowsNoProofForThem() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		long id = importImage(running, cookie);
		connect(running, cookie);
		send(running, cookie, "POST", "/api/x-import/sync", "");
		send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-1\"}");
		send(running, cookie, "POST", "/api/illustrations/" + id + "/ai-analysis", "");

		try (Unwritable settings = settingsCannotBeReplaced()) {
			assertEquals(204, send(running, cookie, "DELETE", "/api/desktop/settings/gemini-key", null).statusCode());
			assertEquals(200, send(running, cookie, "POST", "/api/x-auth/disconnect", "").statusCode());
			assertTrue(json(send(running, cookie, "GET", "/api/desktop/settings", null)).path("geminiVerifiedAt").isNull());
			assertTrue(json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("verifiedAt").isNull());
			assertFalse(Files.exists(credentialPath()));
		}
	}

	@Test
	void aKeyThatCannotBeSavedSaysSoKeepsTheOldOneAndSavingAgainWorks() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-1\"}").statusCode());
		var keys = running.context().getBean(com.yuutara.illustrationarchive.service.GeminiKeyStore.class);
		Path keyFile = home.resolve("config").resolve("gemini-key.txt");
		Files.delete(keyFile);
		Files.createDirectories(keyFile.resolve("in-the-way"));

		HttpResponse<String> refused = send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-2\"}");
		assertEquals(500, refused.statusCode(), refused.body());
		assertFalse(refused.body().contains("test-key-2"));
		assertEquals(200, send(running, cookie, "GET", "/api/desktop/settings", null).statusCode(), "Settings still opens with an unreadable key");

		Files.delete(keyFile.resolve("in-the-way"));
		Files.delete(keyFile);
		assertEquals(204, send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"test-key-2\"}").statusCode());
		assertEquals("test-key-2", keys.apiKey());
	}

	@Test
	void credentialsNeverComeBackFromTheApiOrReachTheLog() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		accessToken = "secret-access-value";
		connect(running, cookie);
		send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"secret-key-value\"}");
		HttpResponse<String> badKey = send(running, cookie, "PUT", "/api/desktop/settings/gemini-key", "{\"apiKey\":\"secret bad value\"}");
		assertEquals(400, badKey.statusCode());

		String answers = badKey.body() + send(running, cookie, "GET", "/api/x-auth/status", null).body()
				+ send(running, cookie, "GET", "/api/desktop/settings", null).body();
		String settingsFile = Files.readString(home.resolve("config").resolve("settings.properties"));
		String log = Files.readString(home.resolve("logs").resolve("desktop.log"));
		for (String secret : List.of("secret-key-value", "secret bad value", "secret-access-value", "refresh-1")) {
			assertFalse(answers.contains(secret), secret);
			assertFalse(settingsFile.contains(secret), secret);
			assertFalse(log.contains(secret), secret);
		}
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
	void theReadLimitChosenInSettingsBoundsTheNextSyncAndSurvivesARestart() throws Exception {
		DesktopServer.Running running = start();
		String cookie = sessionCookie(running);
		JsonNode initial = json(send(running, cookie, "GET", "/api/x-auth/status", null));
		assertEquals(List.of(25, 50, 100, 200), initial.path("scanBudgets").valueStream().map(JsonNode::asInt).toList());

		HttpResponse<String> saved = send(running, cookie, "PUT", "/api/desktop/settings/x-read-limit", "{\"reads\":25}");
		assertEquals(200, saved.statusCode(), saved.body());
		assertEquals(25, json(saved).path("scanBudget").asInt());
		assertEquals(400, send(running, cookie, "PUT", "/api/desktop/settings/x-read-limit", "{\"reads\":77}").statusCode());
		assertEquals(400, send(running, cookie, "PUT", "/api/desktop/settings/x-read-limit", "{}").statusCode());
		assertEquals(25, json(send(running, cookie, "GET", "/api/x-auth/status", null)).path("scanBudget").asInt());
		assertEquals(25, new DesktopSettings(running.context().getEnvironment().getProperty(DesktopServer.HOME_PROPERTY)).reads());

		connect(running, cookie);
		HttpResponse<String> stale = send(running, cookie, "POST", "/api/x-import/sync?readLimit=50", "");
		assertEquals(409, stale.statusCode(), stale.body());
		assertEquals("READ_LIMIT_CHANGED", json(stale).path("code").asText());
		assertEquals(25, json(stale).path("readLimit").asInt());
		assertTrue(apiPaths.isEmpty(), "a sync for a limit the page no longer shows reads nothing from X");
		JsonNode report = json(send(running, cookie, "POST", "/api/x-import/sync?readLimit=25", ""));
		assertEquals(25, report.path("scanBudget").asInt(), report.toString());
		assertEquals(25, report.path("newBudget").asInt(), report.toString());
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
		return start(Map.of());
	}

	private DesktopServer.Running start(Map<String, String> overrides) {
		Map<String, String> properties = new HashMap<>(Map.of(
				"x.api.base-url", "http://127.0.0.1:" + fakeX.getAddress().getPort(),
				"gemini.base-url", "http://127.0.0.1:" + fakeX.getAddress().getPort(),
				"illustration-archive.desktop.x-callback-port", Integer.toString(callbackPort)));
		properties.putAll(overrides);
		DesktopServer.Running running = DesktopServer.start(home, 0, true, properties);
		started.add(running);
		return running;
	}

	private long importImage(DesktopServer.Running running, String cookie) throws Exception {
		String boundary = "illustration-archive-test";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(png(40));
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> imported = http.send(HttpRequest.newBuilder(running.baseUri().resolve("/api/illustrations/import/single"))
				.header("Cookie", cookie).header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
		assertTrue(imported.statusCode() < 300, imported.body());
		return json(send(running, cookie, "GET", "/api/illustrations?page=0&size=48", null)).path("items").get(0).path("id").asLong();
	}

	private static String geminiAnswer() {
		String analysis = JSON.writeValueAsString(Map.of("summary", "摘要", "characters", List.of(), "relationships", List.of(),
				"suggestedTags", List.of(), "pages", List.of(Map.of("index", 1, "description", "画面", "texts", List.of()))));
		return JSON.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP",
				"content", Map.of("parts", List.of(Map.of("text", analysis)))))));
	}

	private JsonNode syncProgress(DesktopServer.Running running, String cookie) throws Exception {
		return json(send(running, cookie, "GET", "/api/x-import/inbox?page=0&size=24", null)).path("syncProgress");
	}

	private HttpResponse<String> answer(DesktopServer.Running running, String cookie, String action, String confirmation) throws Exception {
		return send(running, cookie, "POST", "/api/x-import/sync/account/" + action, "{\"confirmation\":\"" + confirmation + "\"}");
	}

	private int statusOf(DesktopServer.Running running, String cookie, String action, String confirmation) {
		try {
			return answer(running, cookie, action, confirmation).statusCode();
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static void seedItem(JdbcTemplate jdbc) {
		jdbc.update("INSERT INTO x_like_item (x_post_id, x_author_id, author_username, author_display_name, post_text, status, discovered_at, updated_at)"
				+ " VALUES ('900', '7', 'artist', 'Artist', 'kept', 'PENDING', '2026-09-03 01:02:03', '2026-09-03 01:02:03')");
	}

	private HeldExchange hold(String code) {
		HeldExchange held = new HeldExchange();
		heldCodes.put(code, held);
		return held;
	}

	/** Comes back with -1 when the app dropped the connection, which is what abandoning a login may do. */
	private CompletableFuture<Integer> callbackAsync(String state, String code) {
		return http.sendAsync(HttpRequest.newBuilder(URI.create(callback() + "?state=" + state + "&code=" + code)).GET().build(),
				HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::statusCode).exceptionally(failure -> -1);
	}

	private void awaitRevocation(String refreshToken) throws InterruptedException {
		for (int wait = 0; wait < 200; wait++) {
			if (revocations.stream().anyMatch(form -> refreshToken.equals(form(form).get("token")))) return;
			Thread.sleep(25);
		}
		throw new AssertionError("Not revoked: " + refreshToken + " in " + revocations);
	}

	private void awaitTokenAnswers(int count) throws InterruptedException {
		for (int wait = 0; wait < 200 && tokenAnswers.get() < count; wait++) {
			Thread.sleep(25);
		}
		assertTrue(tokenAnswers.get() >= count);
		Thread.sleep(100); // the app's side of the answer is what the caller goes on to check
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

	private interface Unwritable extends AutoCloseable {
		@Override
		void close() throws IOException;
	}

	private Path settingsFile() {
		return home.resolve("config").resolve("settings.properties");
	}

	/**
	 * Only settings.properties cannot be replaced; the Key and token files beside it still can. A non-empty directory
	 * in its place refuses the rename on every platform, where an open file would only do so on Windows. Closing puts
	 * back exactly what was there, which is how the old record reaches a restart.
	 */
	private Unwritable settingsCannotBeReplaced() throws IOException {
		byte[] saved = Files.readAllBytes(settingsFile());
		Files.delete(settingsFile());
		Path inTheWay = Files.createDirectories(settingsFile().resolve("in-the-way"));
		return () -> {
			Files.delete(inTheWay);
			Files.delete(settingsFile());
			Files.write(settingsFile(), saved);
		};
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
		Map<String, String> fields = form(body);
		boolean refresh = fields.get("grant_type").equals("refresh_token");
		String code = fields.get("code");
		HeldExchange held = code == null ? null : heldCodes.get(code);
		if (held != null) held.block();
		String refreshToken = refresh ? "refresh-2" : "the-code".equals(code) ? "refresh-1" : "refresh-" + code;
		reply(exchange, tokenStatus, "{\"token_type\":\"bearer\",\"expires_in\":7200,\"access_token\":\"" + accessToken
				+ "\",\"refresh_token\":\"" + refreshToken + "\"}");
		tokenAnswers.incrementAndGet();
	}

	/** Lets a test keep X "thinking" over one token request, to act while the app waits for the answer. */
	private static final class HeldExchange {
		private final CountDownLatch arrived = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);

		void block() {
			arrived.countDown();
			try {
				assertTrue(released.await(20, TimeUnit.SECONDS));
			} catch (InterruptedException interrupted) {
				throw new IllegalStateException(interrupted);
			}
		}

		void awaitArrival() throws InterruptedException {
			assertTrue(arrived.await(10, TimeUnit.SECONDS), "the token request never arrived");
		}

		void release() {
			released.countDown();
		}
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
				+ "]},\"meta\":{\"result_count\":3" + nextToken(exchange) + "}}";
	}

	/** With {@code pagedLikes} the Likes never end, so a sync always leaves a cursor saved. */
	private String nextToken(HttpExchange exchange) {
		if (!pagedLikes) return "";
		String query = exchange.getRequestURI().getRawQuery();
		int page = Arrays.stream(query.split("&")).filter(part -> part.startsWith("pagination_token=cursor-"))
				.mapToInt(part -> Integer.parseInt(part.substring("pagination_token=cursor-".length()))).findFirst().orElse(0);
		return ",\"next_token\":\"cursor-" + (page + 1) + "\"";
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
