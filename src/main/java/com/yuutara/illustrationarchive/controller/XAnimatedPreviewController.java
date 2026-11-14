package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.XAnimatedPreviewService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class XAnimatedPreviewController {
	private final XAnimatedPreviewService previews;

	public XAnimatedPreviewController(XAnimatedPreviewService previews) {
		this.previews = previews;
	}

	@GetMapping("/api/x-import/inbox/{itemId}/media/{mediaKey}/content")
	public void content(@PathVariable long itemId, @PathVariable String mediaKey,
			@RequestParam(required = false) String size,
			HttpServletResponse response) throws IOException {
		try (var preview = previews.openMedia(itemId, mediaKey, size)) {
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(preview.contentType());
			response.setHeader("Cache-Control", "no-store");
			response.setHeader("X-Content-Type-Options", "nosniff");
			if (preview.contentLength() >= 0) response.setContentLengthLong(preview.contentLength());
			// Write directly to the servlet stream, without Resource's automatic Range handling.
			preview.body().transferTo(response.getOutputStream());
		}
	}
}
