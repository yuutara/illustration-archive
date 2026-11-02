package com.yuutara.illustrationarchive.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Loopback stand-ins for X and Gemini, so the whole configure, authorize, sync and analyze path can be reviewed in a
 * browser with no account, no spending and no way out. The outcome depends only on the made-up value typed in:
 * {@link #REJECTED_CLIENT} fails the token exchange, {@link #GOOD_KEY} analyzes, {@link #QUOTA_KEY} is rate limited,
 * and any other key is refused. The authorize page offers allow, deny and fail.
 */
final class FakeIntegrations implements AutoCloseable {

	static final String REJECTED_CLIENT = "fake-rejected-client";
	static final String GOOD_KEY = "fake-good-key";
	static final String QUOTA_KEY = "fake-quota-key";
	static final String ACCOUNT = "fake_collector";
	private static final String USER_ID = "4242";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpServer server;

	FakeIntegrations() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/i/oauth2/authorize", this::authorizePage);
		server.createContext("/2/oauth2/token", this::token);
		server.createContext("/2/oauth2/revoke", exchange -> reply(exchange, 200, "{\"revoked\":true}"));
		server.createContext("/2/users/me", exchange -> reply(exchange, 200,
				"{\"data\":{\"id\":\"" + USER_ID + "\",\"name\":\"Fake\",\"username\":\"" + ACCOUNT + "\"}}"));
		server.createContext("/2/users/", exchange -> reply(exchange, 200, "{\"meta\":{\"result_count\":0}}"));
		server.createContext("/v1beta/models/", this::analyze);
		server.setExecutor(Thread::startVirtualThread);
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** The only way past the addresses {@link DesktopServer} pins, and it is not reachable from a packaged app. */
	Map<String, String> overrides() {
		return Map.of("x.api.base-url", baseUrl(), "gemini.base-url", baseUrl(),
				"illustration-archive.desktop.x-authorize-url", baseUrl() + "/i/oauth2/authorize");
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void authorizePage(HttpExchange exchange) throws IOException {
		Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
		String back = query.get("redirect_uri") + "?state=" + encode(query.get("state"));
		String page = "<!doctype html><meta charset=\"utf-8\"><title>模拟 X 授权</title><h1>模拟 X 授权页</h1>"
				+ "<p>这是本机的模拟页面，不是 X。Client ID：" + encode(query.get("client_id")) + "</p><ul>"
				+ "<li><a id=\"allow\" href=\"" + back + "&code=fake-code\">同意授权</a></li>"
				+ "<li><a id=\"deny\" href=\"" + back + "&error=access_denied\">拒绝授权</a></li>"
				+ "<li><a id=\"fail\" href=\"" + back + "&error=server_error\">X 返回错误</a></li></ul>";
		byte[] body = page.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private void token(HttpExchange exchange) throws IOException {
		Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		if (REJECTED_CLIENT.equals(fields.get("client_id"))) {
			reply(exchange, 401, "{\"error\":\"unauthorized_client\"}");
			return;
		}
		reply(exchange, 200, "{\"token_type\":\"bearer\",\"expires_in\":7200,\"access_token\":\"fake-access\",\"refresh_token\":\"fake-refresh\"}");
	}

	private void analyze(HttpExchange exchange) throws IOException {
		String key = exchange.getRequestHeaders().getFirst("x-goog-api-key");
		JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
		if (QUOTA_KEY.equals(key)) {
			reply(exchange, 429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"fake quota\"}}");
		} else if (!GOOD_KEY.equals(key)) {
			reply(exchange, 400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"fake rejection\"}}");
		} else {
			reply(exchange, 200, answer((int) request.path("contents").get(0).path("parts").valueStream()
					.filter(part -> part.has("inlineData")).count()));
		}
	}

	private static String answer(int images) {
		var pages = IntStream.rangeClosed(1, images).mapToObj(index -> Map.of("index", index, "description", "模拟分析：第 " + index + " 张",
				"texts", List.of(Map.of("source", "こんにちは", "translation", "你好", "note", "模拟文字")))).toList();
		String analysis = JSON.writeValueAsString(Map.of("summary", "这是模拟服务返回的分析，没有调用真实 Gemini。", "pages", pages,
				"characters", List.of(), "relationships", List.of(), "suggestedTags", List.of("模拟")));
		return JSON.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP",
				"content", Map.of("parts", List.of(Map.of("text", analysis)))))));
	}

	private static Map<String, String> form(String encoded) {
		Map<String, String> values = new HashMap<>();
		Arrays.stream(encoded == null ? new String[0] : encoded.split("&")).map(pair -> pair.split("=", 2))
				.filter(pair -> pair.length == 2)
				.forEach(pair -> values.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
		return values;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static void reply(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
