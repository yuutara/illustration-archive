package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.XAnimatedPreviewService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class XAnimatedPreviewControllerTest {
	@Test
	void streamsMp4WithHeadersAndIgnoresRangeAndExternalUrlQuery() throws Exception {
		var service = mock(XAnimatedPreviewService.class);
		var body = new Body();
		when(service.openMedia(7, "16_123", null))
				.thenReturn(new XAnimatedPreviewService.MediaPreview(body, 4, "video/mp4"));
		var mvc = MockMvcBuilders.standaloneSetup(new XAnimatedPreviewController(service)).build();
		mvc.perform(get("/api/x-import/inbox/7/media/16_123/content?url=https://evil.example/a.mp4")
				.header("Range", "bytes=0-1"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("video/mp4"))
				.andExpect(header().string("Content-Length", "4"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().doesNotExist("Content-Range"))
				.andExpect(content().bytes(new byte[]{1, 2, 3, 4}));
		assertTrue(body.closed);
		verify(service).openMedia(7, "16_123", null);
		verifyNoMoreInteractions(service);
	}

	@Test
	void streamsPhotoPreviewWithRequestedSizeAndImageContentType() throws Exception {
		var service = mock(XAnimatedPreviewService.class);
		var body = new Body();
		when(service.openMedia(9, "3_img", "small"))
				.thenReturn(new XAnimatedPreviewService.MediaPreview(body, 4, "image/png"));
		var mvc = MockMvcBuilders.standaloneSetup(new XAnimatedPreviewController(service)).build();
		mvc.perform(get("/api/x-import/inbox/9/media/3_img/content?size=small"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("image/png"))
				.andExpect(header().string("Content-Length", "4"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(content().bytes(new byte[]{1, 2, 3, 4}));
		assertTrue(body.closed);
		verify(service).openMedia(9, "3_img", "small");
	}

	@Test
	void missingBadSizeAndRejectedUpstreamReturnSafeStatusInsteadOfContent() throws Exception {
		var service = mock(XAnimatedPreviewService.class);
		var mvc = MockMvcBuilders.standaloneSetup(new XAnimatedPreviewController(service)).build();
		when(service.openMedia(7, "g", null)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
		mvc.perform(get("/api/x-import/inbox/7/media/g/content")).andExpect(status().isNotFound());
		when(service.openMedia(7, "g", "orig")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
		mvc.perform(get("/api/x-import/inbox/7/media/g/content?size=orig")).andExpect(status().isBadRequest());
		doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY)).when(service).openMedia(7, "g", null);
		mvc.perform(get("/api/x-import/inbox/7/media/g/content")).andExpect(status().isBadGateway());
	}

	@Test
	void streamingWithoutKnownLengthClosesUpstreamEvenWhenClientWriteFails() throws Exception {
		var service = mock(XAnimatedPreviewService.class);
		var body = new Body();
		when(service.openMedia(7, "g", null))
				.thenReturn(new XAnimatedPreviewService.MediaPreview(body, -1, "video/mp4"));
		var response = mock(HttpServletResponse.class);
		var output = mock(ServletOutputStream.class);
		when(response.getOutputStream()).thenReturn(output);
		doThrow(new IOException("client disconnected")).when(output).write(any(byte[].class), anyInt(), anyInt());
		assertThrows(IOException.class, () -> new XAnimatedPreviewController(service).content(7, "g", null, response));
		assertTrue(body.closed);
		verify(response, never()).setContentLengthLong(anyLong());
	}

	private static class Body extends ByteArrayInputStream {
		boolean closed;
		Body() { super(new byte[]{1, 2, 3, 4}); }
		@Override public void close() { closed = true; }
	}
}
