package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class XApiClientTest {
	private static final String ME = """
			{"data":{"id":"999999999999999999999999999999"}}
			""";

	@Test
	void mapsOneAndMultiplePhotosInAttachmentOrderAndAuthorExpansion() throws Exception {
		List<HttpRequest> requests = new ArrayList<>();
		XApiClient client = client(requests, response(200, ME), response(200, """
				{"data":[
				  {"id":"11","author_id":"a2","text":"single","created_at":"2026-09-25T09:00:00Z","attachments":{"media_keys":["p1"]}},
				  {"id":"12","author_id":"a1","text":"multi","attachments":{"media_keys":["p3","p2"]}}
				],"includes":{"users":[{"id":"a1","name":"Artist One","username":"one"},{"id":"a2","name":"Artist Two","username":"two"}],
				"media":[{"media_key":"p1","type":"photo","url":"https://img/1","width":100,"height":200},
				{"media_key":"p2","type":"photo","url":"https://img/2","width":300,"height":400},
				{"media_key":"p3","type":"photo","url":"https://img/3","width":500,"height":600}]},
				"meta":{"next_token":"opaque"}}
				"""));

		XLikePage page = client.fetchRecentLikes(client.resolveUserId(), 5, null);

		assertEquals(2, page.candidates().size());
		assertTrue(page.hasMore());
		assertEquals("opaque", page.nextToken());
		assertEquals(XLikeStatus.PENDING, page.candidates().get(0).status());
		assertEquals("a2", page.candidates().get(0).xAuthorId());
		assertEquals("two", page.candidates().get(0).authorUsername());
		assertEquals("Artist Two", page.candidates().get(0).authorDisplayName());
		assertEquals("single", page.candidates().get(0).postText());
		assertEquals("https://img/1", page.candidates().get(0).media().get(0).sourceUrl());
		assertEquals(100, page.candidates().get(0).media().get(0).width());
		assertEquals(XLikeStatus.PENDING, page.candidates().get(1).status());
		assertEquals(List.of("p3", "p2"), page.candidates().get(1).media().stream().map(m -> m.mediaKey()).toList());
		assertEquals(List.of(0, 1), page.candidates().get(1).media().stream().map(m -> m.sortOrder()).toList());
		assertEquals(2, requests.size());
		assertTrue(requests.get(1).uri().toString().contains("max_results=5"));
		assertTrue(requests.get(1).uri().toString().contains("expansions=author_id,attachments.media_keys"));
		assertTrue(requests.get(1).uri().toString().contains("tweet.fields=author_id,attachments,created_at,text"));
		assertTrue(requests.get(1).uri().toString().contains("media.fields=media_key,type,url,width,height"));
		assertTrue(requests.get(1).uri().toString().contains("/2/users/999999999999999999999999999999/liked_tweets"));
	}

	@Test
	void noMediaGifVideoAndMixedAttachmentsAreUnsupported() throws Exception {
		XApiClient client = client(new ArrayList<>(), response(200, ME), response(200, """
				{"data":[
				  {"id":"1","author_id":"a","text":"none"},
				  {"id":"2","author_id":"a","attachments":{"media_keys":["g"]}},
				  {"id":"3","author_id":"a","attachments":{"media_keys":["v"]}},
				  {"id":"4","author_id":"a","attachments":{"media_keys":["p","v"]}},
				  {"id":"5","author_id":"a","attachments":{"media_keys":["missing"]}}
				],"includes":{"users":[{"id":"a","name":"A","username":"a"}],
				"media":[{"media_key":"g","type":"animated_gif"},{"media_key":"v","type":"video"},
				{"media_key":"p","type":"photo","url":"https://img/p"}]},"meta":{}}
				"""));

		XLikePage page = client.fetchRecentLikes(client.resolveUserId(), 5, null);

		assertFalse(page.hasMore());
		assertEquals(null, page.nextToken());
		assertEquals(5, page.candidates().size());
		assertTrue(page.candidates().stream().allMatch(p -> p.status() == XLikeStatus.UNSUPPORTED));
		assertEquals(2, page.candidates().get(3).media().size());
	}

	@Test
	void animatedVariantsAndMixedMediaKeepVideoUnsupported() throws Exception {
		List<HttpRequest> requests = new ArrayList<>();
		var client = client(requests, response(200, """
				{"data":[
				 {"id":"1","author_id":"a","attachments":{"media_keys":["single"]}},
				 {"id":"2","author_id":"a","attachments":{"media_keys":["multi"]}},
				 {"id":"3","author_id":"a","attachments":{"media_keys":["fallback"]}},
				 {"id":"4","author_id":"a","attachments":{"media_keys":["bad"]}},
				 {"id":"5","author_id":"a","attachments":{"media_keys":["photo","single"]}},
				 {"id":"6","author_id":"a","attachments":{"media_keys":["photo","video"]}}
				],"includes":{"users":[{"id":"a","name":"A","username":"a"}],"media":[
				 {"media_key":"photo","type":"photo","url":"https://pbs.twimg.com/media/p.jpg"},
				 {"media_key":"single","type":"animated_gif","variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/a.mp4"}]},
				 {"media_key":"multi","type":"animated_gif","variants":[
				  {"content_type":"video/mp4","url":"https://video.twimg.com/fallback.mp4"},
				  {"content_type":"video/mp4","bit_rate":100,"url":"https://video.twimg.com/low.mp4"},
				  {"content_type":"video/mp4","bit_rate":200,"url":"https://video.twimg.com/high.mp4?tag=1"},
				  {"content_type":"video/mp4","bit_rate":200,"url":"https://video.twimg.com/tie.mp4"},
				  {"content_type":"video/mp4","bit_rate":999,"url":"https://evil.example/a.mp4"},
				  {"content_type":"application/x-mpegURL","bit_rate":1000,"url":"https://video.twimg.com/a.m3u8"}]},
				 {"media_key":"fallback","type":"animated_gif","variants":[
				  {"content_type":"video/mp4","bit_rate":"900","url":"https://video.twimg.com/first.mp4"},
				  {"content_type":"video/mp4","url":"https://video.twimg.com/second.mp4"}]},
				 {"media_key":"bad","type":"animated_gif","variants":[{"content_type":"video/mp4","url":"http://video.twimg.com/a.mp4"}]},
				 {"media_key":"video","type":"video","variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/v.mp4"}]}
				]}}
				"""));
		var page = client.fetchRecentLikes("1", 10, null);
		assertEquals(List.of(XLikeStatus.PENDING, XLikeStatus.PENDING, XLikeStatus.PENDING,
				XLikeStatus.UNSUPPORTED, XLikeStatus.PENDING, XLikeStatus.UNSUPPORTED),
				page.candidates().stream().map(p -> p.status()).toList());
		assertEquals("https://video.twimg.com/high.mp4?tag=1", page.candidates().get(1).media().get(0).sourceUrl());
		assertEquals("https://video.twimg.com/first.mp4", page.candidates().get(2).media().get(0).sourceUrl());
		assertEquals(List.of(0, 1), page.candidates().get(4).media().stream().map(m -> m.sortOrder()).toList());
		assertTrue(requests.get(0).uri().getQuery().contains("variants,preview_image_url"));
	}

	@Test
	void handlesUpstreamErrorsWithoutExposingToken() throws Exception {
		for (int status : List.of(401, 403, 429, 500, 503)) {
			XApiClient client = client(new ArrayList<>(), response(status, "secret-from-response"));
			XApiException error = assertThrows(XApiException.class, client::resolveUserId);
			assertEquals(status, error.upstreamStatus());
			assertFalse(error.getMessage().contains("test-secret"));
			assertFalse(error.getMessage().contains("secret-from-response"));
			assertFalse(error.getMessage().contains("Authorization"));
			if (status == 401) {
				assertTrue(error.getMessage().contains("after one refresh attempt"));
				assertFalse(error.getMessage().contains("expired"));
			} else if (status == 403) {
				assertTrue(error.getMessage().contains("invalid credential"));
				assertTrue(error.getMessage().contains("not valid for this endpoint"));
				assertTrue(error.getMessage().contains("app or user permissions"));
				assertFalse(error.getMessage().contains("recognized"));
			} else if (status == 429) {
				assertEquals("X API rate limit reached; retry manually later.", error.getMessage());
			} else {
				assertEquals("X API request failed with HTTP " + status + ".", error.getMessage());
			}
		}
	}

	@Test
	void authenticationDiagnosticsAlsoApplyToLikedTweetsRequest() throws Exception {
		for (int status : List.of(401, 403)) {
			XApiClient client = client(new ArrayList<>(), response(200, ME),
					response(status, "secret-from-response"));
			String userId = client.resolveUserId();

			XApiException error = assertThrows(XApiException.class,
					() -> client.fetchRecentLikes(userId, 5, null));

			assertEquals(status, error.upstreamStatus());
			assertFalse(error.getMessage().contains("test-secret"));
			assertFalse(error.getMessage().contains("secret-from-response"));
		}
	}

	@Test
	void disabledClientAndInvalidSizeNeverCallHttp() {
		HttpClient http = mock(HttpClient.class);
		XTokenManager tokens = mock(XTokenManager.class);
		XApiClient disabled = new XApiClient(http, new ObjectMapper(), false, "https://api.x.com", tokens);
		assertThrows(XApiException.class, disabled::resolveUserId);
		XApiClient enabled = new XApiClient(http, new ObjectMapper(), true, "https://api.x.com", tokens);
		assertThrows(IllegalArgumentException.class, () -> enabled.fetchRecentLikes("999", 4, null));
		assertThrows(IllegalArgumentException.class, () -> enabled.fetchRecentLikes("999", 101, null));
		verifyNoInteractions(http);
		verifyNoInteractions(tokens);
	}

	@Test
	void retriesOnceWithRefreshedTokenOn401() throws Exception {
		List<HttpRequest> requests = new ArrayList<>();
		XTokenManager tokens = mock(XTokenManager.class);
		when(tokens.currentAccessToken()).thenReturn("old-access");
		when(tokens.refreshAfterRejection("old-access")).thenReturn("new-access");
		XApiClient client = client(requests, tokens, response(401, "secret-from-response"), response(200, ME));

		assertEquals("999999999999999999999999999999", client.resolveUserId());
		assertEquals(2, requests.size());
		assertEquals("Bearer old-access", requests.get(0).headers().firstValue("Authorization").orElseThrow());
		assertEquals("Bearer new-access", requests.get(1).headers().firstValue("Authorization").orElseThrow());
		verify(tokens).refreshAfterRejection("old-access");
	}

	@Test
	void doesNotRefreshOn403() throws Exception {
		XTokenManager tokens = mock(XTokenManager.class);
		when(tokens.currentAccessToken()).thenReturn("test-secret");
		XApiClient client = client(new ArrayList<>(), tokens, response(403, "secret-from-response"));

		assertThrows(XApiException.class, client::resolveUserId);
		verify(tokens, never()).refreshAfterRejection(any());
	}

	@Test
	void sendsEncodedPaginationTokenAndTreatsBlankNextTokenAsEnd() throws Exception {
		List<HttpRequest> requests = new ArrayList<>();
		XApiClient client = client(requests, response(200, ME), response(200, """
				{"meta":{"next_token":"  "}}
				"""));

		XLikePage page = client.fetchRecentLikes(client.resolveUserId(), 5, "opaque+with/slash");

		assertFalse(page.hasMore());
		assertEquals(null, page.nextToken());
		assertTrue(requests.get(1).uri().toString().contains("pagination_token=opaque%2Bwith%2Fslash"));
	}

	@Test
	void multiplePagesResolveUserIdOnlyOnce() throws Exception {
		List<HttpRequest> requests = new ArrayList<>();
		XApiClient client = client(requests, response(200, ME),
				response(200, "{\"meta\":{\"next_token\":\"next\"}}"),
				response(200, "{\"meta\":{}}"));

		String userId = client.resolveUserId();
		XLikePage first = client.fetchRecentLikes(userId, 5, null);
		XLikePage second = client.fetchRecentLikes(userId, 5, first.nextToken());

		assertFalse(second.hasMore());
		assertEquals(3, requests.size());
		assertTrue(requests.get(0).uri().toString().endsWith("/2/users/me"));
		assertTrue(requests.get(1).uri().toString().contains("/2/users/999999999999999999999999999999/liked_tweets"));
		assertTrue(requests.get(2).uri().toString().contains("pagination_token=next"));
	}

	@SuppressWarnings("unchecked")
	private XApiClient client(List<HttpRequest> requests, HttpResponse<String>... responses) throws Exception {
		XTokenManager tokens = mock(XTokenManager.class);
		when(tokens.currentAccessToken()).thenReturn("test-secret");
		when(tokens.refreshAfterRejection("test-secret")).thenReturn("new-secret");
		return client(requests, tokens, responses);
	}

	@SuppressWarnings("unchecked")
	private XApiClient client(List<HttpRequest> requests, XTokenManager tokens,
			HttpResponse<String>... responses) throws Exception {
		HttpClient http = mock(HttpClient.class);
		int[] index = {0};
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return responses[Math.min(index[0]++, responses.length - 1)];
		});
		return new XApiClient(http, new ObjectMapper(), true, "https://api.x.com", tokens);
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> response(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		return response;
	}
}
