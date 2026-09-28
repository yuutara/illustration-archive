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

@Component
public class XTokenManager {
	private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(1);
	private static final URI TOKEN_ENDPOINT = URI.create("https://api.x.com/2/oauth2/token");

	private final HttpClient httpClient;
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
			@Value("${x.oauth.client-secret:}") String clientSecret) {
		this(HttpClient.newHttpClient(), objectMapper, store, clientId, clientSecret, TOKEN_ENDPOINT, Clock.systemUTC());
	}

	XTokenManager(HttpClient httpClient, ObjectMapper objectMapper, XTokenCredentialStore store,
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

	private void refresh() {
		if (clientId.isBlank() || clientSecret.isBlank()) {
			throw new XApiException("X OAuth Client ID or Client Secret is missing from local runtime configuration.", null);
		}
		String basic = Base64.getEncoder().encodeToString((form(clientId) + ":" + form(clientSecret))
				.getBytes(StandardCharsets.UTF_8));
		String body = "grant_type=refresh_token&refresh_token=" + form(credentials.refreshToken());
		HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
				.timeout(Duration.ofSeconds(20))
				.header("Authorization", "Basic " + basic)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new XApiException("X OAuth token refresh failed with HTTP " + response.statusCode()
						+ ". Check the App credentials or generate a new token pair in X Developer Console.", response.statusCode());
			}
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
			if (newRefreshToken.isBlank()) newRefreshToken = credentials.refreshToken();
			Instant expiresAt = null;
			JsonNode expiresIn = json.path("expires_in");
			if (!expiresIn.isMissingNode() && !expiresIn.isNull()) {
				long seconds = expiresIn.asLong(-1);
				if (seconds <= 0) {
					throw new XApiException("X OAuth token refresh response contained an invalid expiration.", null);
				}
				expiresAt = clock.instant().plusSeconds(seconds);
			}
			XTokenCredentialStore.Credentials updated =
					new XTokenCredentialStore.Credentials(accessToken, newRefreshToken, expiresAt);
			credentials = updated;
			refreshedWithoutExpiry = expiresAt == null;
			pendingPersistence = true;
			persistPending();
		} catch (IOException e) {
			throw new XApiException("X OAuth token refresh network or response error.", null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new XApiException("X OAuth token refresh was interrupted.", null);
		}
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
