package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class XAnimatedPreviewServiceTest {
	private final XLikeMediaRepository media = mock(XLikeMediaRepository.class);
	private final HttpClient http = mock(HttpClient.class);
	private final XAnimatedPreviewService service = new XAnimatedPreviewService(media, http);

	@Test
	void resolvesDatabaseUrlAndLeavesBodyUnreadForStreamingCaller() throws Exception {
		var body = new Body();
		when(media.findByItemId(7)).thenReturn(List.of(attachment("animated_gif", "https://video.twimg.com/a.mp4?tag=1")));
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(call -> {
					HttpRequest request = call.getArgument(0);
					assertEquals("https://video.twimg.com/a.mp4?tag=1", request.uri().toString());
					assertEquals("video/mp4", request.headers().firstValue("Accept").orElseThrow());
					assertTrue(request.headers().firstValue("Range").isEmpty());
					return response(200, body, Map.of("Content-Type", List.of("video/mp4"), "Content-Length", List.of("4")));
				});
		try (var preview = service.open(7, "g")) {
			assertSame(body, preview.body());
			assertEquals(4, preview.contentLength());
			assertEquals(4, body.available(), "Service must not consume or buffer the body");
			assertArrayEquals(new byte[]{1, 2, 3, 4}, preview.body().readAllBytes());
		}
		assertTrue(body.closed);
		verify(media).findByItemId(7);
	}

	@Test
	void missingItemMissingMediaAndNonAnimatedTypesNeverContactUpstream() {
		when(media.findByItemId(7)).thenReturn(List.of());
		assertStatus(404, () -> service.open(7, "g"));
		when(media.findByItemId(7)).thenReturn(List.of(attachment("animated_gif", "https://video.twimg.com/a.mp4")));
		assertStatus(404, () -> service.open(7, "missing"));
		for (String type : List.of("photo", "video", "unknown")) {
			when(media.findByItemId(7)).thenReturn(List.of(attachment(type, "https://video.twimg.com/a.mp4")));
			assertStatus(404, () -> service.open(7, "g"));
		}
		verifyNoInteractions(http);
	}

	@Test
	void reusesStrictHttpsCdnValidationEvenForStoredUrls() {
		for (String url : List.of("http://video.twimg.com/a.mp4", "https://video.twimg.com.evil/a.mp4",
				"https://evil.example/a.mp4", "https://video.twimg.com/a.m3u8", "https://video.twimg.com:443/a.mp4",
				"https://user@video.twimg.com/a.mp4", "https://video.twimg.com/a.mp4#x")) {
			when(media.findByItemId(7)).thenReturn(List.of(attachment("animated_gif", url)));
			assertStatus(502, () -> service.open(7, "g"));
		}
		verifyNoInteractions(http);
	}

	@Test
	void rejectsRedirectErrorsPartialContentAndNonMp4AndClosesTheirBodies() throws Exception {
		when(media.findByItemId(7)).thenReturn(List.of(attachment("animated_gif", "https://video.twimg.com/a.mp4")));
		for (int status : List.of(302, 403, 404, 500, 206)) {
			var body = new Body();
			var upstream = response(status, body, Map.of("Content-Type", List.of("video/mp4")));
			when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
					.thenReturn(upstream);
			assertStatus(502, () -> service.open(7, "g"));
			assertTrue(body.closed);
		}
		for (Map<String, List<String>> headers : List.of(Map.of("Content-Type", List.of("text/html")),
				Map.of("Content-Type", List.of("video/mp4"), "Content-Length", List.of("invalid")))) {
			var body = new Body();
			var upstream = response(200, body, headers);
			when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
			assertStatus(502, () -> service.open(7, "g"));
			assertTrue(body.closed);
		}
	}

	@Test
	void networkFailureAndInterruptionReturnSafeGatewayError() throws Exception {
		when(media.findByItemId(7)).thenReturn(List.of(attachment("animated_gif", "https://video.twimg.com/a.mp4")));
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenThrow(new IOException("private upstream detail"));
		assertStatus(502, () -> service.open(7, "g"));
		doThrow(new InterruptedException()).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		try {
			assertStatus(502, () -> service.open(7, "g"));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally { Thread.interrupted(); }
	}

	@Test
	void proxiesPhotoWithNormalizedSizeAndBaseOverrideAndRejectsInvalidSizeOrType() throws Exception {
		var custom = new XAnimatedPreviewService(media, http, "http://127.0.0.1:18080", "http://127.0.0.1:18080");
		when(media.findByItemId(9)).thenReturn(List.of(attachment("photo", "https://pbs.twimg.com/media/Abc123.png")));
		var smallBody = new Body();
		var largeBody = new Body();
		var fullBody = new Body();
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenAnswer(call -> {
					HttpRequest req = call.getArgument(0);
					assertEquals("http://127.0.0.1:18080/media/Abc123?format=png&name=small", req.uri().toString());
					assertEquals("image/jpeg, image/png", req.headers().firstValue("Accept").orElseThrow());
					return response(200, smallBody, Map.of("Content-Type", List.of("image/png; charset=binary"), "Content-Length", List.of("4")));
				})
				.thenAnswer(call -> {
					HttpRequest req = call.getArgument(0);
					assertEquals("http://127.0.0.1:18080/media/Abc123?format=png&name=large", req.uri().toString());
					return response(200, largeBody, Map.of("Content-Type", List.of("image/jpeg"), "Content-Length", List.of("4")));
				})
				.thenAnswer(call -> {
					HttpRequest req = call.getArgument(0);
					assertEquals("http://127.0.0.1:18080/media/Abc123?format=png&name=4096x4096", req.uri().toString());
					return response(200, fullBody, Map.of("Content-Type", List.of("image/png"), "Content-Length", List.of("4")));
				});
		try (var preview = custom.openMedia(9, "g", "small")) {
			assertEquals("image/png", preview.contentType());
			assertEquals(4, preview.contentLength());
			assertArrayEquals(new byte[]{1, 2, 3, 4}, preview.body().readAllBytes());
		}
		assertTrue(smallBody.closed);
		try (var preview = custom.openMedia(9, "g", null)) {
			assertEquals("image/jpeg", preview.contentType());
		}
		assertTrue(largeBody.closed);
		try (var preview = custom.openMedia(9, "g", "4096x4096")) {
			assertEquals("image/png", preview.contentType());
		}
		assertTrue(fullBody.closed);

		assertStatus(400, () -> custom.openMedia(9, "g", "orig"));
		var badTypeBody = new Body();
		var badResp = response(200, badTypeBody, Map.of("Content-Type", List.of("image/svg+xml")));
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(badResp);
		assertStatus(502, () -> custom.openMedia(9, "g", "4096x4096"));
		assertTrue(badTypeBody.closed);

		for (String badPhoto : List.of("http://pbs.twimg.com/media/Abc123.png", "https://evil.example/media/Abc123.png",
				"https://pbs.twimg.com/media/Abc123.webp", "https://pbs.twimg.com/other/Abc123.png",
				"https://pbs.twimg.com/media/Abc123")) {
			when(media.findByItemId(9)).thenReturn(List.of(attachment("photo", badPhoto)));
			assertStatus(502, () -> custom.openMedia(9, "g", "4096x4096"));
		}
		verify(http, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
		assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
	}
	private XLikeMedia attachment(String type, String url) { return new XLikeMedia("g", 0, type, url, 320, 240); }
	@SuppressWarnings("unchecked")
	private HttpResponse<InputStream> response(int status, InputStream body, Map<String, List<String>> headers) {
		var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
		return response;
	}
	private static class Body extends ByteArrayInputStream {
		boolean closed;
		Body() { super(new byte[]{1, 2, 3, 4}); }
		@Override public void close() { closed = true; }
	}
}
