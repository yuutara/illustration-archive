package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** Inbox preview only: the caller owns the upstream stream, and nothing is archived or cached. */
@Service
public class XAnimatedPreviewService {
	private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png");

	private final XLikeMediaRepository media;
	private final Supplier<HttpClient> client;
	private final String photoCdnBaseUrl;
	private final String videoCdnBaseUrl;

	@Autowired
	public XAnimatedPreviewService(XLikeMediaRepository media,
			@Value("${x.media.photo-cdn-base-url:https://pbs.twimg.com}") String photoCdnBaseUrl,
			@Value("${x.media.video-cdn-base-url:https://video.twimg.com}") String videoCdnBaseUrl) {
		this(media, new LazyHttpClient(), photoCdnBaseUrl, videoCdnBaseUrl); // default NEVER follows redirects
	}

	XAnimatedPreviewService(XLikeMediaRepository media, HttpClient client) {
		this(media, client, XPhotoMediaUrl.DEFAULT_BASE_URL, XAnimatedMediaUrl.DEFAULT_BASE_URL);
	}

	XAnimatedPreviewService(XLikeMediaRepository media, HttpClient client, String photoCdnBaseUrl, String videoCdnBaseUrl) {
		this(media, () -> client, photoCdnBaseUrl, videoCdnBaseUrl);
	}

	private XAnimatedPreviewService(XLikeMediaRepository media, Supplier<HttpClient> client,
			String photoCdnBaseUrl, String videoCdnBaseUrl) {
		this.media = media;
		this.client = client;
		this.photoCdnBaseUrl = photoCdnBaseUrl;
		this.videoCdnBaseUrl = videoCdnBaseUrl;
	}

	public Preview open(long itemId, String mediaKey) {
		var attachment = findMedia(itemId, mediaKey);
		if (!"animated_gif".equals(attachment.mediaType())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		MediaPreview opened = openAnimated(attachment);
		return new Preview(opened.body(), opened.contentLength());
	}

	public MediaPreview openMedia(long itemId, String mediaKey, String size) {
		var attachment = findMedia(itemId, mediaKey);
		if ("animated_gif".equals(attachment.mediaType())) {
			if (size != null && !size.isBlank()) {
				normalizeSize(size);
			}
			return openAnimated(attachment);
		}
		if ("photo".equals(attachment.mediaType())) {
			return openPhoto(attachment, normalizeSize(size));
		}
		throw new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	private XLikeMedia findMedia(long itemId, String mediaKey) {
		return media.findByItemId(itemId).stream()
				.filter(candidate -> candidate.mediaKey().equals(mediaKey)).findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static String normalizeSize(String size) {
		try {
			return XPhotoMediaUrl.normalizePreviewSize(size);
		} catch (IllegalArgumentException failure) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage());
		}
	}

	private MediaPreview openAnimated(XLikeMedia attachment) {
		URI uri;
		try {
			uri = XAnimatedMediaUrl.resolve(attachment.sourceUrl(), videoCdnBaseUrl);
		} catch (IllegalArgumentException failure) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Animated preview source is invalid.");
		}
		return fetchUpstream(uri, "video/mp4", Set.of("video/mp4"), "Animated preview");
	}

	private MediaPreview openPhoto(XLikeMedia attachment, String size) {
		URI uri;
		try {
			uri = XPhotoMediaUrl.resolve(attachment.sourceUrl(), size, photoCdnBaseUrl).uri();
		} catch (IllegalArgumentException failure) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Photo preview source is invalid.");
		}
		return fetchUpstream(uri, "image/jpeg, image/png", PHOTO_TYPES, "Photo preview");
	}

	private MediaPreview fetchUpstream(URI uri, String acceptHeader, Set<String> allowedTypes, String label) {
		var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
				.header("Accept", acceptHeader).GET().build();
		try {
			var upstream = client.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
			InputStream body = upstream.body();
			try {
				// A complete 200 response only; never expose redirects, error bodies or partial content.
				String contentType = upstream.headers().firstValue("Content-Type").orElse("")
						.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
				if (upstream.statusCode() != 200 || !allowedTypes.contains(contentType)) {
					throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, label + " upstream is unavailable.");
				}
				long length = upstream.headers().firstValueAsLong("Content-Length").orElse(-1);
				if (length < -1) throw new IllegalArgumentException("Invalid upstream length.");
				return new MediaPreview(body, length, contentType);
			} catch (RuntimeException failure) {
				try { body.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
				throw failure;
			}
		} catch (IOException | IllegalArgumentException failure) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, label + " upstream is unavailable.");
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, label + " request was interrupted.");
		}
	}

	public record Preview(InputStream body, long contentLength) implements AutoCloseable {
		@Override public void close() throws IOException { body.close(); }
	}

	public record MediaPreview(InputStream body, long contentLength, String contentType) implements AutoCloseable {
		@Override public void close() throws IOException { body.close(); }
	}
}
