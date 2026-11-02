package com.yuutara.illustrationarchive.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import com.yuutara.illustrationarchive.service.XApiException;
import com.yuutara.illustrationarchive.service.XTokenCredentialStore;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <p>There is one live login, {@code attempt}, from {@link #start} until it ends. Its callback claims it and then
 * trades the code for tokens outside the lock, because that takes as long as X takes; the attempt stays current
 * meanwhile. Cancelling, disconnecting and starting over all end it by replacing that field, and only a callback
 * still holding the current attempt may save tokens or report an outcome. So whatever an abandoned attempt learns
 * afterwards is thrown away, and it cannot touch the state of the login that replaced it. Expiry only applies while
 * waiting for the callback: once claimed, the token request's own timeout bounds the wait.
 */
@Service
@Profile(DesktopServer.PROFILE)
public class XAuthorizationService {

	public static final String SCOPES = "tweet.read users.read like.read offline.access";
	static final String CALLBACK_PATH = "/x/callback";
	private static final Logger log = LoggerFactory.getLogger(XAuthorizationService.class);
	private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{8,128}");

	public enum State { DISCONNECTED, WAITING, CONNECTED, FAILED }

	/**
	 * {@code state} is the login in progress or how the last one ended; {@code savedLogin} says separately what is on
	 * disk, because a reconnect that is waiting or failed leaves the existing login in place and still usable.
	 * {@code failure} is a code the page turns into words; it never carries upstream text.
	 */
	public record Status(State state, String failure, String authorizeUrl, XTokenManager.SavedLogin savedLogin,
			boolean browserOpened) {
	}

	private record Attempt(String state, String verifier, String clientId, String authorizeUrl, HttpServer server,
			AtomicBoolean stopped) {
		/** Both the callback and whoever abandons the attempt get here, and a server must only stop once. */
		void stop() {
			if (stopped.compareAndSet(false, true)) server.stop(0);
		}
	}

	private final XTokenManager tokens;
	private final DesktopSettings settings;
	private final DesktopExternal external;
	private final XLikeSyncStateRepository syncState;
	private final DesktopEvidence evidence;
	private final URI authorizeEndpoint;
	private final int callbackPort;
	private final Duration attemptLifetime;
	private final SecureRandom random = new SecureRandom();
	private Attempt attempt;
	private boolean exchanging;
	private boolean browserOpened;
	private String failure;

	public XAuthorizationService(XTokenManager tokens, DesktopSettings settings, DesktopExternal external,
			XLikeSyncStateRepository syncState, DesktopEvidence evidence,
			@Value("${illustration-archive.desktop.x-authorize-url}") String authorizeEndpoint,
			@Value("${illustration-archive.desktop.x-callback-port}") int callbackPort,
			@Value("${illustration-archive.desktop.x-authorization-seconds:300}") int attemptSeconds) {
		this.tokens = tokens;
		this.settings = settings;
		this.external = external;
		this.syncState = syncState;
		this.evidence = evidence;
		this.authorizeEndpoint = URI.create(authorizeEndpoint);
		this.callbackPort = callbackPort;
		this.attemptLifetime = Duration.ofSeconds(attemptSeconds);
	}

	/** The exact value to register as the Callback URL of the user's X app. */
	public String callbackUrl() {
		return "http://127.0.0.1:" + callbackPort + CALLBACK_PATH;
	}

	public synchronized Status status() {
		XTokenManager.SavedLogin saved = tokens.savedLogin();
		if (attempt != null) return new Status(State.WAITING, null, exchanging ? null : attempt.authorizeUrl(), saved, browserOpened);
		if (failure != null) return new Status(State.FAILED, failure, null, saved, false);
		return new Status(saved == XTokenManager.SavedLogin.USABLE ? State.CONNECTED : State.DISCONNECTED, null, null, saved, false);
	}

	public synchronized Status start(String clientId) {
		if (clientId == null || !CLIENT_ID.matcher(clientId).matches()) {
			throw new IllegalArgumentException("Client ID format is not valid.");
		}
		// First, so that a value that cannot be saved leaves any login in progress as it was.
		settings.saveXClientId(clientId);
		abandonAttempt();
		failure = null;
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
		Attempt started = new Attempt(state, verifier, clientId, authorizeUrl, server, new AtomicBoolean());
		server.createContext(CALLBACK_PATH, exchange -> handleCallback(started, exchange));
		// A handler on the dispatcher thread would keep the port bound for as long as X takes to answer,
		// and starting over needs that port back straight away.
		server.setExecutor(Thread::startVirtualThread);
		server.start();
		attempt = started;
		CompletableFuture.delayedExecutor(attemptLifetime.toSeconds(), TimeUnit.SECONDS).execute(() -> expire(started));
		browserOpened = external.browse(URI.create(authorizeUrl));
		return status();
	}

	public synchronized void cancel() {
		abandonAttempt();
		failure = null;
	}

	/** @return false when local tokens were removed but X could not be told to revoke them */
	public synchronized boolean disconnect() {
		abandonAttempt();
		failure = null;
		boolean revoked = tokens.disconnect();
		forgetObservedAccount();
		evidence.loginChanged();
		return revoked;
	}

	/** A refused account is a fact about the old credentials. If this fails, the next sync simply looks again. */
	private void forgetObservedAccount() {
		try {
			syncState.forgetObservedAccount();
		} catch (RuntimeException failed) {
			log.warn("Could not clear the account a sync last refused.", failed);
		}
	}

	@PreDestroy
	synchronized void abandonAttempt() {
		if (attempt != null) {
			attempt.stop();
			attempt = null;
			exchanging = false;
		}
	}

	private synchronized void expire(Attempt expired) {
		if (attempt == expired && !exchanging) {
			abandonAttempt();
			failure = "TIMED_OUT";
		}
	}

	private synchronized boolean claim(Attempt candidate) {
		if (attempt != candidate || exchanging) return false;
		exchanging = true;
		return true;
	}

	/** @return false when this login was abandoned during the exchange, so the tokens must not be used */
	private synchronized boolean install(Attempt current, XTokenCredentialStore.Credentials issued) {
		if (attempt != current) return false;
		tokens.install(issued);
		forgetObservedAccount();
		evidence.loginChanged();
		finish(current, null);
		return true;
	}

	private synchronized void finish(Attempt current, String outcome) {
		if (attempt != current) return;
		attempt = null;
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
			if (claimed) current.stop();
		}
	}

	private void finishLogin(Attempt current, Map<String, String> query, HttpExchange exchange) throws IOException {
		String code = query.get("code");
		if (code == null || code.isBlank()) {
			finish(current, "access_denied".equals(query.get("error")) ? "DENIED" : "REJECTED");
			respond(exchange, 200, "没有完成授权", "没有连接 X 账号。可以关闭这个页面，回到插画档案。");
			return;
		}
		XTokenCredentialStore.Credentials issued;
		try {
			issued = tokens.exchange(current.clientId(), code, current.verifier(), callbackUrl());
		} catch (XApiException rejected) {
			log.warn("X authorization code exchange failed. upstreamStatus={}", rejected.upstreamStatus());
			finish(current, rejected.upstreamStatus() == null ? "NETWORK" : "EXCHANGE_FAILED");
			respond(exchange, 200, "没有完成授权", "X 没有接受这次授权，请回到插画档案查看原因。");
			return;
		}
		try {
			if (install(current, issued)) {
				respond(exchange, 200, "授权已完成", "还没有读取任何 X 数据。可以关闭这个页面，回到插画档案的 X 收藏，点“同步”时才会读取并确认账号。");
				return;
			}
			revokeUnused(issued);
			respond(exchange, 409, "这次授权已取消", "没有保存这次授权。可以关闭这个页面，回到插画档案。");
		} catch (XApiException unsaved) {
			log.warn("The tokens of a finished X login could not be saved.");
			finish(current, "SAVE_FAILED");
			revokeUnused(issued);
			respond(exchange, 200, "没有完成授权", "本机没能保存这次授权，请回到插画档案查看原因。");
		}
	}

	/** X has already issued these, so they would stay valid with nobody holding them. */
	private void revokeUnused(XTokenCredentialStore.Credentials issued) {
		if (!tokens.revoke(issued)) log.warn("X could not be told to revoke the tokens of an unfinished login.");
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

	private String authorizeUrl(String clientId, String state, String challenge) {
		Map<String, String> parameters = new LinkedHashMap<>();
		parameters.put("response_type", "code");
		parameters.put("client_id", clientId);
		parameters.put("redirect_uri", callbackUrl());
		parameters.put("scope", SCOPES);
		parameters.put("state", state);
		parameters.put("code_challenge", challenge);
		parameters.put("code_challenge_method", "S256");
		return authorizeEndpoint + "?" + parameters.entrySet().stream()
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
