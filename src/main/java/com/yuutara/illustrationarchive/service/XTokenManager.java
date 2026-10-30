package com.yuutara.illustrationarchive.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;

@Component
public class XTokenManager {
	private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(1);

	private final Supplier<HttpClient> httpClient;
	private final ObjectMapper objectMapper;
	private final XTokenCredentialStore store;
	private final String clientId;
	private final String clientSecret;
	private final URI tokenEndpoint;
	private final Clock clock;
	private XTokenCredentialStore.Credentials credentials;
	private boolean refreshedWithoutExpiry;
	private boolean pendingPersistence;

	@Autowired
	public XTokenManager(ObjectMapper objectMapper, XTokenCredentialStore store,
			@Value("${x.oauth.client-id:}") String clientId,
			@Value("${x.oauth.client-secret:}") String clientSecret,
			@Value("${x.api.base-url:https://api.x.com}") String baseUrl) {
		this(new LazyHttpClient(), objectMapper, store, clientId, clientSecret,
				URI.create(baseUrl.replaceAll("/+$", "") + "/2/oauth2/token"), Clock.systemUTC());
	}

	XTokenManager(HttpClient httpClient, ObjectMapper objectMapper, XTokenCredentialStore store,
			String clientId, String clientSecret, URI tokenEndpoint, Clock clock) {
		this(() -> httpClient, objectMapper, store, clientId, clientSecret, tokenEndpoint, clock);
	}

	private XTokenManager(Supplier<HttpClient> httpClient, ObjectMapper objectMapper, XTokenCredentialStore store,
			String clientId, String clientSecret, URI tokenEndpoint, Clock clock) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.store = store;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.tokenEndpoint = tokenEndpoint;
		this.clock = clock;
	}

	synchronized String currentAccessToken() {
		if (credentials == null) credentials = store.load();
		persistPending();
		if (credentials.accessToken().isBlank() || (credentials.expiresAt() == null && !refreshedWithoutExpiry)
				|| (credentials.expiresAt() != null
					&& !credentials.expiresAt().isAfter(clock.instant().plus(EXPIRY_MARGIN)))) {
			refresh();
		}
		return credentials.accessToken();
	}

	synchronized String refreshAfterRejection(String rejectedToken) {
		if (credentials == null) credentials = store.load();
		persistPending();
		if (!credentials.accessToken().equals(rejectedToken) && !credentials.accessToken().isBlank()) {
			return credentials.accessToken();
		}
		refresh();
		return credentials.accessToken();
	}

	/** True when a local token pair exists; never contacts X. */
	public synchronized boolean connected() {
		return store.exists();
	}

	/**
	 * Finishes the PKCE authorization: a public client proves itself with the code verifier, so no secret is
	 * sent or stored. The Client ID stays with the tokens because every later refresh must repeat it.
	 */
	public synchronized void connect(String publicClientId, String code, String codeVerifier, String redirectUri) {
		String body = "grant_type=authorization_code&code=" + form(code) + "&client_id=" + form(publicClientId)
				+ "&redirect_uri=" + form(redirectUri) + "&code_verifier=" + form(codeVerifier);
		HttpResponse<String> response = post(tokenEndpoint, body, null, "X OAuth authorization");
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new XApiException("X OAuth authorization failed with HTTP " + response.statusCode() + ".", response.statusCode());
		}
		XTokenCredentialStore.Credentials issued = readTokens(response, "", publicClientId);
		if (issued.refreshToken().isBlank()) {
			throw new XApiException("X OAuth authorization did not return a refresh token.", null);
		}
		store.save(issued);
		credentials = issued;
		refreshedWithoutExpiry = issued.expiresAt() == null;
		pendingPersistence = false;
	}

	/**
	 * Local tokens go first, so a failed network call can never leave the account connected on this machine.
	 *
	 * @return false when the local tokens are gone but X could not be told to revoke them
	 */
	public synchronized boolean disconnect() {
		XTokenCredentialStore.Credentials current = credentials;
		if (current == null && store.exists()) {
			try {
				current = store.load();
			} catch (XApiException unreadable) {
				current = null;
			}
		}
		store.delete();
		credentials = null;
		refreshedWithoutExpiry = false;
		pendingPersistence = false;
		if (current == null || current.clientId().isBlank()) return current == null;
		String body = "token=" + form(current.refreshToken()) + "&client_id=" + form(current.clientId());
		try {
			int status = post(tokenEndpoint.resolve("revoke"), body, null, "X OAuth revoke").statusCode();
			return status >= 200 && status < 300;
		} catch (XApiException unreachable) {
			return false;
		}
	}

	private void refresh() {
		boolean publicClient = !credentials.clientId().isBlank();
		if (!publicClient && (clientId.isBlank() || clientSecret.isBlank())) {
			throw new XApiException("X OAuth Client ID or Client Secret is missing from local runtime configuration.", null);
		}
		String body = "grant_type=refresh_token&refresh_token=" + form(credentials.refreshToken());
		String basic = null;
		if (publicClient) body += "&client_id=" + form(credentials.clientId());
		else basic = Base64.getEncoder().encodeToString((form(clientId) + ":" + form(clientSecret))
				.getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> response = post(tokenEndpoint, body, basic, "X OAuth token refresh");
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new XApiException("X OAuth token refresh failed with HTTP " + response.statusCode()
					+ ". Check the App credentials or generate a new token pair in X Developer Console.", response.statusCode());
		}
		XTokenCredentialStore.Credentials updated = readTokens(response, credentials.refreshToken(), credentials.clientId());
		credentials = updated;
		refreshedWithoutExpiry = updated.expiresAt() == null;
		pendingPersistence = true;
		persistPending();
	}

	private HttpResponse<String> post(URI endpoint, String body, String basicCredentials, String action) {
		HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (basicCredentials != null) request.header("Authorization", "Basic " + basicCredentials);
		try {
			return httpClient.get().send(request.build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new XApiException(action + " network or response error.", null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new XApiException(action + " was interrupted.", null);
		}
	}

	private XTokenCredentialStore.Credentials readTokens(HttpResponse<String> response, String previousRefreshToken,
			String tokenClientId) {
		JsonNode json;
		try {
			json = objectMapper.readTree(response.body());
		} catch (RuntimeException e) {
			throw new XApiException("X OAuth token refresh returned an invalid response.", null);
		}
		if (json == null) {
			throw new XApiException("X OAuth token refresh returned an invalid response.", null);
		}
		String accessToken = json.path("access_token").asText("");
		if (accessToken.isBlank() || XTokenCredentialStore.containsWhitespace(accessToken)) {
			throw new XApiException("X OAuth token refresh response did not contain an access token.", null);
		}
		String newRefreshToken = json.path("refresh_token").asText("");
		if (XTokenCredentialStore.containsWhitespace(newRefreshToken)) {
			throw new XApiException("X OAuth token refresh response contained a malformed refresh token.", null);
		}
		if (newRefreshToken.isBlank()) newRefreshToken = previousRefreshToken;
		Instant expiresAt = null;
		JsonNode expiresIn = json.path("expires_in");
		if (!expiresIn.isMissingNode() && !expiresIn.isNull()) {
			long seconds = expiresIn.asLong(-1);
			if (seconds <= 0) {
				throw new XApiException("X OAuth token refresh response contained an invalid expiration.", null);
			}
			expiresAt = clock.instant().plusSeconds(seconds);
		}
		return new XTokenCredentialStore.Credentials(accessToken, newRefreshToken, expiresAt, tokenClientId);
	}

	private void persistPending() {
		if (!pendingPersistence) return;
		store.save(credentials);
		pendingPersistence = false;
	}

	private String form(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
