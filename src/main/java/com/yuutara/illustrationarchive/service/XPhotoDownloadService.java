package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Supplier;

@Service
public class XPhotoDownloadService {
	private static final Logger log = LoggerFactory.getLogger(XPhotoDownloadService.class);

	private final Supplier<HttpClient> httpClient;
	private final FileStorage fileStorageService;
	private final String photoCdnBaseUrl;

	@Autowired
	public XPhotoDownloadService(FileStorage fileStorageService,
			@Value("${x.media.photo-cdn-base-url:https://pbs.twimg.com}") String photoCdnBaseUrl) {
		this(new LazyHttpClient(), fileStorageService, photoCdnBaseUrl);
	}

	XPhotoDownloadService(HttpClient httpClient, FileStorage fileStorageService) {
		this(httpClient, fileStorageService, XPhotoMediaUrl.DEFAULT_BASE_URL);
	}

	XPhotoDownloadService(HttpClient httpClient, FileStorage fileStorageService, String photoCdnBaseUrl) {
		this(() -> httpClient, fileStorageService, photoCdnBaseUrl);
	}

	private XPhotoDownloadService(Supplier<HttpClient> httpClient, FileStorage fileStorageService, String photoCdnBaseUrl) {
		this.httpClient = httpClient;
		this.fileStorageService = fileStorageService;
		this.photoCdnBaseUrl = photoCdnBaseUrl;
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

		XPhotoMediaUrl.PhotoVariant photoRequest = XPhotoMediaUrl.resolve(media.sourceUrl(), "4096x4096", photoCdnBaseUrl);
		HttpRequest request = HttpRequest.newBuilder(photoRequest.uri())
				.timeout(Duration.ofSeconds(60))
				.header("Accept", "image/jpeg, image/png")
				.GET().build();
		StoredFile storedFile = null;
		try {
			HttpResponse<InputStream> response = httpClient.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
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

	private record ImageSize(int width, int height) {
	}
}
