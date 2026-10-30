package com.yuutara.illustrationarchive.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuutara.illustrationarchive.service.XApiException;
import com.yuutara.illustrationarchive.service.XTokenManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OAuth 2.0 Authorization Code with PKCE for a Windows desktop app, as a public client: nothing secret ships in
 * the EXE, and the code verifier proves that the app which started the login is the one finishing it.
 *
 * <p>X redirects the system browser back to a short-lived listener on a fixed loopback port. It is separate from
 * the application server for two reasons. X matches callback URLs exactly, so the port cannot vary, while the
 * application server may fall back to a random port. And the redirect arrives from x.com, so the SameSite=Strict
 * session cookie is absent and {@link LocalAccessFilter} would have to gain an unauthenticated path. Here the only
 * thing listening is one path that accepts one {@code state} value for at most five minutes.
 *
 * <p>Connecting makes no billable call: the code exchange is OAuth, and no X API endpoint is read until the user
 * asks for a sync.
 */
@Service
@Profile(DesktopServer.PROFILE)
public class XAuthorizationService {

	public static final String SCOPES = "tweet.read users.read like.read offline.access";
	static final String CALLBACK_PATH = "/x/callback";
	private static final Logger log = LoggerFactory.getLogger(XAuthorizationService.class);
	private static final URI AUTHORIZE_ENDPOINT = URI.create("https://x.com/i/oauth2/authorize");
	private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{8,128}");

	public enum State { DISCONNECTED, WAITING, CONNECTED, FAILED }

	/** {@code failure} is a code the page turns into words; it never carries upstream text. */
	public record Status(State state, String failure, String authorizeUrl) {
	}

	private record Attempt(String state, String verifier, String clientId, String authorizeUrl, HttpServer server) {
	}

	private final XTokenManager tokens;
	private final DesktopSettings settings;
	private final DesktopExternal external;
	private final int callbackPort;
	private final Duration attemptLifetime;
	private final SecureRandom random = new SecureRandom();
	private Attempt attempt;
	private boolean exchanging;
	private String failure;

	public XAuthorizationService(XTokenManager tokens, DesktopSettings settings, DesktopExternal external,
			@Value("${illustration-archive.desktop.x-callback-port}") int callbackPort,
			@Value("${illustration-archive.desktop.x-authorization-seconds:300}") int attemptSeconds) {
		this.tokens = tokens;
		this.settings = settings;
		this.external = external;
		this.callbackPort = callbackPort;
		this.attemptLifetime = Duration.ofSeconds(attemptSeconds);
	}

	/** The exact value to register as the Callback URL of the user's X app. */
	public String callbackUrl() {
		return "http://127.0.0.1:" + callbackPort + CALLBACK_PATH;
	}

	public synchronized Status status() {
		if (attempt != null) return new Status(State.WAITING, null, attempt.authorizeUrl());
		if (exchanging) return new Status(State.WAITING, null, null);
		if (failure != null) return new Status(State.FAILED, failure, null);
		return new Status(tokens.connected() ? State.CONNECTED : State.DISCONNECTED, null, null);
	}

	public synchronized Status start(String clientId) {
		if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
			throw new IllegalArgumentException("Client ID format is not valid.");
		}
		closeAttempt();
		failure = null;
		settings.saveXClientId(clientId);
		String state = randomToken();
		String verifier = randomToken();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(
					InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), callbackPort), 0);
		} catch (IOException busy) {
			log.warn("X authorization callback port {} is unavailable.", callbackPort, busy);
			failure = "CALLBACK_PORT_BUSY";
			return status();
		}
		String authorizeUrl = authorizeUrl(clientId, state, challenge(verifier));
		Attempt started = new Attempt(state, verifier, clientId, authorizeUrl, server);
		server.createContext(CALLBACK_PATH, exchange -> handleCallback(started, exchange));
		server.start();
		attempt = started;
		CompletableFuture.delayedExecutor(attemptLifetime.toSeconds(), TimeUnit.SECONDS).execute(() -> expire(started));
		external.browse(URI.create(authorizeUrl));
		return status();
	}

	public synchronized void cancel() {
		closeAttempt();
		failure = null;
	}

	/** @return false when local tokens were removed but X could not be told to revoke them */
	public synchronized boolean disconnect() {
		closeAttempt();
		failure = null;
		return tokens.disconnect();
	}

	@PreDestroy
	synchronized void closeAttempt() {
		if (attempt != null) {
			stop(attempt.server());
			attempt = null;
		}
	}

	private synchronized void expire(Attempt expired) {
		if (attempt == expired) {
			closeAttempt();
			failure = "TIMED_OUT";
		}
	}

	private synchronized boolean claim(Attempt candidate) {
		if (attempt != candidate) return false;
		attempt = null;
		exchanging = true;
		return true;
	}

	private synchronized void finish(String outcome) {
		exchanging = false;
		failure = outcome;
	}

	private void handleCallback(Attempt current, HttpExchange exchange) throws IOException {
		boolean claimed = false;
		try {
			try (exchange) {
				Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
				if (!"GET".equals(exchange.getRequestMethod()) || !sameState(current.state(), query.get("state"))) {
					// A stray or forged request must not end the login the user is in the middle of.
					respond(exchange, 400, "无法识别这次授权", "请回到插画档案重新开始。");
					return;
				}
				claimed = claim(current);
				if (!claimed) {
					respond(exchange, 409, "这次授权已经处理过", "请回到插画档案查看结果。");
					return;
				}
				finishLogin(current, query, exchange);
			}
		} finally {
			if (claimed) stop(current.server());
		}
	}

	private void finishLogin(Attempt current, Map<String, String> query, HttpExchange exchange) throws IOException {
		String code = query.get("code");
		if (code == null || code.isBlank()) {
			finish("access_denied".equals(query.get("error")) ? "DENIED" : "REJECTED");
			respond(exchange, 200, "没有完成授权", "没有连接 X 账号。可以关闭这个页面，回到插画档案。");
			return;
		}
		try {
			tokens.connect(current.clientId(), code, current.verifier(), callbackUrl());
			finish(null);
			respond(exchange, 200, "已连接 X 账号", "可以关闭这个页面，回到插画档案。");
		} catch (XApiException rejected) {
			log.warn("X authorization code exchange failed. upstreamStatus={}", rejected.upstreamStatus());
			finish(rejected.upstreamStatus() == null ? "NETWORK" : "EXCHANGE_FAILED");
			respond(exchange, 200, "没有完成授权", "X 没有接受这次授权，请回到插画档案查看原因。");
		}
	}

	private static void respond(HttpExchange exchange, int status, String title, String message) throws IOException {
		byte[] body = ("<!doctype html><meta charset=\"utf-8\"><title>" + title + "</title>"
				+ "<body style=\"font:16px/1.6 'Segoe UI Variable','Microsoft YaHei',sans-serif;margin:18vh auto;"
				+ "max-width:28em;padding:0 24px;color:#1b1c1f\"><h1 style=\"font-size:20px;font-weight:600\">"
				+ title + "</h1><p>" + message + "</p>").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
	}

	/** Stopping waits for the dispatcher, which may be the caller, so it runs on another thread. */
	private static void stop(HttpServer server) {
		CompletableFuture.runAsync(() -> server.stop(0));
	}

	private String authorizeUrl(String clientId, String state, String challenge) {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("response_type", "code");
		parameters.put("client_id", clientId);
		parameters.put("redirect_uri", callbackUrl());
		parameters.put("scope", SCOPES);
		parameters.put("state", state);
		parameters.put("code_challenge", challenge);
		parameters.put("code_challenge_method", "S256");
		return AUTHORIZE_ENDPOINT + "?" + parameters.entrySet().stream()
				.map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"))
				.collect(Collectors.joining("&"));
	}

	private String randomToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

	private static boolean sameState(String expected, String actual) {
		return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> parameters = new HashMap<>();
		if (raw == null) return parameters;
		for (String pair : raw.split("&")) {
			int separator = pair.indexOf('=');
			if (separator > 0) {
				parameters.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
			}
		}
		return parameters;
	}
}
