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
		assertEquals("https://img/1", page.candidates().get(0).media().get(0).photoUrl());
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
	void handlesUpstreamErrorsWithoutExposingToken() throws Exception {
		for (int status : List.of(401, 403, 429, 500, 503)) {
			XApiClient client = client(new ArrayList<>(), response(status, "secret-from-response"));
			XApiException error = assertThrows(XApiException.class, client::resolveUserId);
			assertEquals(status, error.upstreamStatus());
			assertFalse(error.getMessage().contains("test-secret"));
			assertFalse(error.getMessage().contains("secret-from-response"));
		}
	}

	@Test
	void disabledClientAndInvalidSizeNeverCallHttp() {
		HttpClient http = mock(HttpClient.class);
		XApiClient disabled = new XApiClient(http, new ObjectMapper(), false, "https://api.x.com", "test-secret");
		assertThrows(XApiException.class, disabled::resolveUserId);
		XApiClient enabled = new XApiClient(http, new ObjectMapper(), true, "https://api.x.com", "test-secret");
		assertThrows(IllegalArgumentException.class, () -> enabled.fetchRecentLikes("999", 4, null));
		assertThrows(IllegalArgumentException.class, () -> enabled.fetchRecentLikes("999", 101, null));
		verifyNoInteractions(http);
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
		HttpClient http = mock(HttpClient.class);
		int[] index = {0};
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return responses[index[0]++];
		});
		return new XApiClient(http, new ObjectMapper(), true, "https://api.x.com", "test-secret");
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> response(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		return response;
	}
}
