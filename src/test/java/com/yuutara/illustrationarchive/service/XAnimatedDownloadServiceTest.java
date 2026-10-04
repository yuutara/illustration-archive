package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class XAnimatedDownloadServiceTest {
	@TempDir Path root;
	private static final byte[] MP4 = {0,0,0,20,'f','t','y','p','i','s','o','m',0,0,0,0,'m','p','4','2'};

	@Test
	void downloadsSelectedVariantUnchangedAndResponseCloseFailureCompensates() throws Exception {
		var http = mock(HttpClient.class);
		var storage = new FileStorageService(root.toString());
		var service = new XAnimatedDownloadService(http, storage);
		var request = new java.util.ArrayList<HttpRequest>();
		@SuppressWarnings("unchecked") var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(new ByteArrayInputStream(MP4));
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
			request.add(call.getArgument(0)); return response;
		});
		String url = "https://video.twimg.com/tweet_video/a.mp4?tag=1";
		var stored = service.download(media("animated_gif", url));
		assertEquals(url, request.get(0).uri().toString());
		assertEquals("video/mp4", stored.mimeType());
		assertArrayEquals(MP4, Files.readAllBytes(root.resolve(stored.storageKey())));
		storage.delete(stored.storageKey());
		when(response.body()).thenReturn(new ByteArrayInputStream(MP4) {
			@Override public void close() throws IOException { throw new IOException("response close"); }
		});
		assertThrows(IllegalStateException.class, () -> service.download(media("animated_gif", url)));
		try (var files = Files.walk(root)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
	}

	@Test
	void rejectsExternalUrlsAndOrdinaryVideoWithoutNetwork() {
		var http = mock(HttpClient.class);
		var service = new XAnimatedDownloadService(http, new FileStorageService(root.toString()));
		for (String url : List.of("http://video.twimg.com/a.mp4", "https://video.twimg.com.evil/a.mp4",
				"https://evil.example/a.mp4", "https://video.twimg.com:443/a.mp4", "https://user@video.twimg.com/a.mp4",
				"https://video.twimg.com/a.m3u8", "https://video.twimg.com/a.mp4#x")) {
			assertThrows(IllegalArgumentException.class, () -> service.download(media("animated_gif", url)));
		}
		assertThrows(IllegalArgumentException.class, () -> service.download(media("video", "https://video.twimg.com/a.mp4")));
		verifyNoInteractions(http);
	}

	@Test
	void redirectAndInterruptedBodyNeverLeaveOriginals() throws Exception {
		var http = mock(HttpClient.class);
		@SuppressWarnings("unchecked") var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
		when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
		when(response.statusCode()).thenReturn(302, 200);
		when(response.body()).thenReturn(new ByteArrayInputStream(MP4), new InputStream() {
			@Override public int read() throws IOException { throw new IOException("broken body"); }
		});
		var service = new XAnimatedDownloadService(http, new FileStorageService(root.toString()));
		for (int i = 0; i < 2; i++) assertThrows(IllegalStateException.class,
				() -> service.download(media("animated_gif", "https://video.twimg.com/a.mp4")));
		try (var files = Files.walk(root)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
	}

	private XLikeMedia media(String type, String url) { return new XLikeMedia("g", 0, type, url, 320, 240); }
}
