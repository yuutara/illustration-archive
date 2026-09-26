package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;

@Service
public class XPhotoDownloadService {
	private static final Logger log = LoggerFactory.getLogger(XPhotoDownloadService.class);
	private static final String CDN_HOST = "pbs.twimg.com";

	private final HttpClient httpClient;
	private final FileStorageService fileStorageService;

	@Autowired
	public XPhotoDownloadService(FileStorageService fileStorageService) {
		this(HttpClient.newHttpClient(), fileStorageService);
	}

	XPhotoDownloadService(HttpClient httpClient, FileStorageService fileStorageService) {
		this.httpClient = httpClient;
		this.fileStorageService = fileStorageService;
	}

	public StoredFile download(XLikeMedia media) {
		if (media == null || !"photo".equals(media.mediaType())) {
			throw new XPhotoDownloadException("Only X photo media can be downloaded.");
		}
		if (media.mediaKey() == null || media.mediaKey().isBlank()
				|| media.width() == null || media.width() <= 0
				|| media.height() == null || media.height() <= 0) {
			throw new XPhotoDownloadException("X photo requires a media key and positive expected dimensions.");
		}

		PhotoRequest photoRequest = photoRequest(media.photoUrl());
		HttpRequest request = HttpRequest.newBuilder(photoRequest.uri())
				.timeout(Duration.ofSeconds(60))
				.header("Accept", "image/jpeg, image/png")
				.GET().build();
		StoredFile storedFile = null;
		try {
			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				int status = response.statusCode();
				if (status < 200 || status >= 300) {
					throw new XPhotoDownloadException("X photo CDN request failed with HTTP " + status + ".", status);
				}
				// Keep response-body ownership here so a close failure after the final move
				// can still trigger compensation for the newly stored file.
				storedFile = fileStorageService.store(photoRequest.filename(), new FilterInputStream(body) {
					@Override
					public void close() {
					}
				});
				ImageSize size = readSize(storedFile.storageKey());
				if (size.width() != media.width() || size.height() != media.height()) {
					throw new FullResolutionMismatchException(media.mediaKey(), media.width(), media.height(),
							size.width(), size.height());
				}
				return storedFile;
			}
		} catch (IOException exception) {
			XPhotoDownloadException failure = new XPhotoDownloadException("Failed to download X photo.", exception);
			if (storedFile != null) {
				cleanup(storedFile.storageKey(), failure);
			}
			throw failure;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new XPhotoDownloadException("X photo download was interrupted.", exception);
		} catch (RuntimeException failure) {
			if (storedFile != null) {
				cleanup(storedFile.storageKey(), failure);
			}
			throw failure;
		}
	}

	private PhotoRequest photoRequest(String sourceUrl) {
		URI source;
		try {
			source = new URI(sourceUrl);
		} catch (URISyntaxException | NullPointerException exception) {
			throw new XPhotoDownloadException("X photo source URL is invalid.", exception);
		}
		if (!"https".equalsIgnoreCase(source.getScheme()) || !CDN_HOST.equalsIgnoreCase(source.getHost())
				|| source.getPort() != -1 || source.getUserInfo() != null || source.getFragment() != null) {
			throw new XPhotoDownloadException("X photo source URL must be an HTTPS X media CDN URL.");
		}
		String path = source.getPath();
		int lastSlash = path == null ? -1 : path.lastIndexOf('/');
		if (lastSlash != "/media/".length() - 1 || !path.startsWith("/media/")) {
			throw new XPhotoDownloadException("X photo source URL has an unsupported media path.");
		}
		String name = path.substring(lastSlash + 1);
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			throw new XPhotoDownloadException("X photo source URL has no supported image extension.");
		}
		String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
		String format = switch (extension) {
			case "jpg", "jpeg" -> "jpg";
			case "png" -> "png";
			default -> throw new XPhotoDownloadException("X photo source URL has an unsupported image extension.");
		};
		String baseName = name.substring(0, dot);
		try {
			URI uri = new URI("https", null, CDN_HOST, -1, "/media/" + baseName,
					"format=" + format + "&name=4096x4096", null);
			return new PhotoRequest(uri, name);
		} catch (URISyntaxException exception) {
			throw new XPhotoDownloadException("X photo CDN URL could not be constructed.", exception);
		}
	}

	private ImageSize readSize(String storageKey) {
		try (InputStream input = fileStorageService.load(storageKey).getInputStream();
			 ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
			if (imageInput == null) {
				throw new XPhotoDownloadException("Stored X photo could not be read as an image.");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext()) {
				throw new XPhotoDownloadException("Stored X photo has no readable image metadata.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(imageInput);
				return new ImageSize(reader.getWidth(0), reader.getHeight(0));
			} finally {
				reader.dispose();
			}
		} catch (IOException | IllegalArgumentException exception) {
			throw new XPhotoDownloadException("Failed to read stored X photo dimensions.", exception);
		}
	}

	private void cleanup(String storageKey, RuntimeException failure) {
		try {
			fileStorageService.delete(storageKey);
		} catch (RuntimeException cleanupException) {
			failure.addSuppressed(cleanupException);
			log.error("Failed to delete X photo after validation failure. storageKey={}", storageKey,
					cleanupException);
		}
	}

	private record PhotoRequest(URI uri, String filename) {
	}

	private record ImageSize(int width, int height) {
	}
}
