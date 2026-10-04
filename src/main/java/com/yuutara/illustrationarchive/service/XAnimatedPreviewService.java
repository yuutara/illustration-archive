package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Inbox preview only: the caller owns the upstream stream, and nothing is archived or cached. */
@Service
public class XAnimatedPreviewService {
	private final XLikeMediaRepository media;
	private final HttpClient client;

	@Autowired
	public XAnimatedPreviewService(XLikeMediaRepository media) {
		this(media, HttpClient.newHttpClient()); // default NEVER follows redirects
	}

	XAnimatedPreviewService(XLikeMediaRepository media, HttpClient client) {
		this.media = media;
		this.client = client;
	}

	public Preview open(long itemId, String mediaKey) {
		var attachment = media.findByItemId(itemId).stream()
				.filter(candidate -> candidate.mediaKey().equals(mediaKey)).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (!"animated_gif".equals(attachment.mediaType())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		URI uri;
		try {
			uri = XAnimatedMediaUrl.parse(attachment.sourceUrl());
		} catch (IllegalArgumentException failure) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Animated preview source is invalid.");
		}
		var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
				.header("Accept", "video/mp4").GET().build();
		try {
			var upstream = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			InputStream body = upstream.body();
			try {
				// A complete 200 response only; never expose redirects, error bodies or partial content.
				String contentType = upstream.headers().firstValue("Content-Type").orElse("");
				if (upstream.statusCode() != 200
						|| !"video/mp4".equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
					throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Animated preview upstream is unavailable.");
				}
				long length = upstream.headers().firstValueAsLong("Content-Length").orElse(-1);
				if (length < -1) throw new IllegalArgumentException("Invalid upstream length.");
				return new Preview(body, length);
			} catch (RuntimeException failure) {
				try { body.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
				throw failure;
			}
		} catch (IOException | IllegalArgumentException failure) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Animated preview upstream is unavailable.");
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Animated preview request was interrupted.");
		}
	}

	public record Preview(InputStream body, long contentLength) implements AutoCloseable {
		@Override public void close() throws IOException { body.close(); }
	}
}
