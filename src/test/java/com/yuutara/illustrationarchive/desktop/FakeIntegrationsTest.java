package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The browser review depends on this stand-in, so its allow path has to work end to end before anyone opens a page. */
class FakeIntegrationsTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@TempDir
	Path home;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final FakeIntegrations fakes = new FakeIntegrations();
	private DesktopServer.Running running;

	FakeIntegrationsTest() throws Exception {
	}

	@AfterEach
	void stop() {
		if (running != null) running.context().close();
		fakes.close();
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void theAuthorizePageLeadsThroughTheAppToAFinishedSync() throws Exception {
		Map<String, String> properties = new HashMap<>(fakes.overrides());
		try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			properties.put("illustration-archive.desktop.x-callback-port", Integer.toString(free.getLocalPort()));
		}
		running = DesktopServer.start(home, 0, true, properties);
		String cookie = http.send(HttpRequest.newBuilder(running.sessionUri()).GET().build(), HttpResponse.BodyHandlers.ofString())
				.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];

		JsonNode waiting = JSON.readTree(send(cookie, "POST", "/api/x-auth/start", "{\"clientId\":\"fake-client-id\"}"));
		String authorizeUrl = waiting.path("status").path("authorizeUrl").asText();
		assertTrue(authorizeUrl.startsWith(fakes.baseUrl()), authorizeUrl);
		String page = http.send(HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
		var allow = Pattern.compile("id=\"allow\" href=\"([^\"]+)\"").matcher(page);
		assertTrue(allow.find(), page);
		assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(allow.group(1))).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());

		JsonNode connected = JSON.readTree(send(cookie, "GET", "/api/x-auth/status", null));
		assertEquals("CONNECTED", connected.path("status").path("state").asText(), connected.toString());
		assertFalse(JSON.readTree(send(cookie, "POST", "/api/x-import/sync", "")).path("partial").asBoolean());
		assertEquals(FakeIntegrations.ACCOUNT, JSON.readTree(send(cookie, "GET", "/api/x-auth/status", null)).path("verifiedAccount").asText());
	}

	private String send(String cookie, String method, String path, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(running.baseUri().resolve(path)).header("Cookie", cookie)
				.header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
	}
}
