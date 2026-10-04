package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Preserves the selected MP4 bytes; FileStorage owns size, container validation and SHA-256. */
@Service
public class XAnimatedDownloadService {
	private final HttpClient client;
	private final FileStorage storage;

	@Autowired
	public XAnimatedDownloadService(FileStorage storage) {
		this(HttpClient.newHttpClient(), storage); // default NEVER redirects
	}

	XAnimatedDownloadService(HttpClient client, FileStorage storage) {
		this.client = client;
		this.storage = storage;
	}

	public StoredFile download(XLikeMedia media) {
		if (media == null || !"animated_gif".equals(media.mediaType())) {
			throw new IllegalArgumentException("Only X animated_gif media can use the MP4 downloader.");
		}
		URI uri = XAnimatedMediaUrl.parse(media.sourceUrl());
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
				.header("Accept", "video/mp4").GET().build();
		StoredFile stored = null;
		try {
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				if (response.statusCode() != 200) {
					throw new IllegalStateException("X animated CDN request failed with HTTP " + response.statusCode() + ".");
				}
				String filename = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
				stored = storage.store(filename, new FilterInputStream(body) {
					@Override public void close() { } // response owns close, including post-store compensation
				});
				return stored;
			}
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("X animated download was interrupted.", failure);
		} catch (IOException | RuntimeException failure) {
			if (stored != null) {
				try { storage.delete(stored.storageKey()); }
				catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
			}
			throw new IllegalStateException("Failed to download X animated media.", failure);
		}
	}
}
