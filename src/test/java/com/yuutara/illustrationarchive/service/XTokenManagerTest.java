package com.yuutara.illustrationarchive.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class XTokenManagerTest {
	private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
	private static final URI TOKEN_ENDPOINT = URI.create("https://api.x.com/2/oauth2/token");
	@TempDir Path directory;

	@Test
	void validAccessTokenIsUsedWithoutRefresh() {
		XTokenCredentialStore store = store();
		store.save(new XTokenCredentialStore.Credentials("valid-access", "valid-refresh", NOW.instant().plusSeconds(3600)));
		HttpClient http = mock(HttpClient.class);

		assertEquals("valid-access", manager(http, store).currentAccessToken());
		verifyNoInteractions(http);
	}

	@Test
	void expiredAccessTokenRefreshesAndPersistsRotatedToken() throws Exception {
		XTokenCredentialStore store = store();
		store.save(new XTokenCredentialStore.Credentials("old-access", "old-refresh", NOW.instant().minusSeconds(1)));
		List<HttpRequest> requests = new ArrayList<>();
		HttpClient http = http(requests, 200, """
				{"access_token":"new-access","refresh_token":"new-refresh","expires_in":7200,"token_type":"bearer"}
				""");

		assertEquals("new-access", manager(http, store).currentAccessToken());
		assertEquals("new-access", store.load().accessToken());
		assertEquals("new-refresh", store.load().refreshToken());
		assertEquals(NOW.instant().plusSeconds(7200), store.load().expiresAt());
		assertEquals(TOKEN_ENDPOINT, requests.get(0).uri());
		assertEquals("POST", requests.get(0).method());
		assertEquals("application/x-www-form-urlencoded", requests.get(0).headers().firstValue("Content-Type").orElseThrow());
		assertEquals("Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=",
				requests.get(0).headers().firstValue("Authorization").orElseThrow());
		try (var files = Files.list(directory)) {
			assertEquals(1, files.count());
		}
	}

	@Test
	void missingRotatedRefreshTokenRetainsPreviousOne() throws Exception {
		XTokenCredentialStore store = store();
		store.save(new XTokenCredentialStore.Credentials("old-access", "old-refresh", NOW.instant().minusSeconds(1)));
		HttpClient http = http(new ArrayList<>(), 200, "{\"access_token\":\"new-access\",\"expires_in\":7200}");

		assertEquals("new-access", manager(http, store).currentAccessToken());
		assertEquals("old-refresh", store.load().refreshToken());
	}

	@Test
	void unknownExpirationRefreshesBeforeRequest() throws Exception {
		XTokenCredentialStore store = store();
		store.save(new XTokenCredentialStore.Credentials("old-access", "old-refresh", null));
		List<HttpRequest> requests = new ArrayList<>();
		HttpClient http = http(requests, 200, "{\"access_token\":\"new-access\"}");
		XTokenManager manager = manager(http, store);

		assertEquals("new-access", manager.currentAccessToken());
		assertEquals("new-access", manager.currentAccessToken());
		assertEquals(1, requests.size());
		assertEquals(null, store.load().expiresAt());
		assertEquals("old-refresh", store.load().refreshToken());
	}

	@Test
	void refreshFailureIsClearAndDoesNotExposeSecrets() throws Exception {
		XTokenCredentialStore store = store();
		store.save(new XTokenCredentialStore.Credentials("old-access", "old-refresh", NOW.instant().minusSeconds(1)));
		HttpClient http = http(new ArrayList<>(), 400, "sensitive-upstream-body");

		XApiException error = assertThrows(XApiException.class, () -> manager(http, store).currentAccessToken());
		assertEquals(400, error.upstreamStatus());
		assertTrue(error.getMessage().contains("token refresh failed"));
		for (String secret : List.of("old-access", "old-refresh", "client-secret", "sensitive-upstream-body")) {
			assertFalse(error.getMessage().contains(secret));
		}
		assertEquals("old-refresh", store.load().refreshToken());
	}

	@Test
	void concurrentRequestsPerformOnlyOneRefresh() throws Exception {
		XTokenCredentialStore store = store();
		store.save(new XTokenCredentialStore.Credentials("old-access", "old-refresh", NOW.instant().minusSeconds(1)));
		HttpClient http = http(new ArrayList<>(), 200, "{\"access_token\":\"new-access\",\"expires_in\":7200}");
		XTokenManager manager = manager(http, store);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(manager::currentAccessToken);
			var second = executor.submit(manager::currentAccessToken);
			assertEquals("new-access", first.get());
			assertEquals("new-access", second.get());
			verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void failedPersistenceKeepsRotatedTokenInMemoryForRetry() throws Exception {
		XTokenCredentialStore store = mock(XTokenCredentialStore.class);
		when(store.load()).thenReturn(new XTokenCredentialStore.Credentials(
				"old-access", "old-refresh", NOW.instant().minusSeconds(1)));
		doThrow(new XApiException("Cannot save refreshed X OAuth credentials.", null))
				.doNothing().when(store).save(any(XTokenCredentialStore.Credentials.class));
		List<HttpRequest> requests = new ArrayList<>();
		HttpClient http = http(requests, 200,
				"{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":7200}");
		XTokenManager manager = manager(http, store);

		assertThrows(XApiException.class, manager::currentAccessToken);
		assertEquals("new-access", manager.currentAccessToken());
		assertEquals(1, requests.size());
		verify(store, times(2)).save(new XTokenCredentialStore.Credentials(
				"new-access", "new-refresh", NOW.instant().plusSeconds(7200)));
	}

	@Test
	void atomicReplacementFailureLeavesExistingDestinationAndNoTemporaryFile() throws Exception {
		Path destination = directory.resolve("tokens.properties");
		Files.createDirectory(destination);
		XTokenCredentialStore store = new XTokenCredentialStore(destination.toString());

		assertThrows(XApiException.class, () -> store.save(
				new XTokenCredentialStore.Credentials("new-access", "new-refresh", null)));
		assertTrue(Files.isDirectory(destination));
		try (var files = Files.list(directory)) {
			assertEquals(List.of(destination), files.toList());
		}
	}

	private XTokenCredentialStore store() {
		return new XTokenCredentialStore(directory.resolve("tokens.properties").toString());
	}

	private XTokenManager manager(HttpClient http, XTokenCredentialStore store) {
		return new XTokenManager(http, new ObjectMapper(), store, "client-id", "client-secret", TOKEN_ENDPOINT, NOW);
	}

	@SuppressWarnings("unchecked")
	private HttpClient http(List<HttpRequest> requests, int status, String body) throws Exception {
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return response;
		});
		return http;
	}
}
