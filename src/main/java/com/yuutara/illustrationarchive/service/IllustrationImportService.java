package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.yuutara.illustrationarchive.storage.ThumbnailStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;

@Service
public class IllustrationImportService {
	private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

	private final FileStorage fileStorageService;
	private final IllustrationPersistenceService illustrationPersistenceService;
	private final ThumbnailStorage thumbnailService;
	private static final Logger log =
			LoggerFactory.getLogger(IllustrationImportService.class);

	public IllustrationImportService(
			FileStorage fileStorageService,
			IllustrationPersistenceService illustrationPersistenceService,
			ThumbnailStorage thumbnailService
	) {
		this.fileStorageService = fileStorageService;
		this.illustrationPersistenceService = illustrationPersistenceService;
		this.thumbnailService = thumbnailService;
	}

	public IllustrationImportResult importSingle(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new FileStorageValidationException("Uploaded file must not be empty.");
		}
		String originalFilename = file.getOriginalFilename();
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new FileStorageValidationException("Uploaded file must have an original filename.");
		}
		if (file.getSize() > MAX_FILE_SIZE) {
			throw new FileStorageValidationException("Uploaded file must not exceed 50 MB.");
		}
		validateExtension(originalFilename);
		requireReadableImage(file);
		try {
			return archiveStoredFile(fileStorageService.store(originalFilename, file.getInputStream()));
		} catch (IOException exception) {
			throw new FileStorageException("Failed to store uploaded file.", exception);
		}
	}

	/**
	 * The storage layer only compares the file signature with the extension, so a file holding nothing but a
	 * "GIF89a" header would count as imported and then never display. Reading the image header is enough to
	 * reject that without decoding every frame of a large GIF.
	 */
	private void requireReadableImage(MultipartFile file) {
		try (InputStream input = file.getInputStream()) {
			requireImageHeader(input);
		} catch (IOException exception) {
			throw new FileStorageException("Failed to store uploaded file.", exception);
		}
	}

	private void requireImageHeader(InputStream input) {
		try (ImageInputStream image = ImageIO.createImageInputStream(input)) {
			Iterator<ImageReader> readers = image == null ? java.util.Collections.emptyIterator() : ImageIO.getImageReaders(image);
			if (!readers.hasNext()) {
				throw new FileStorageValidationException("Uploaded file is not a supported image format.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(image);
				if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) {
					throw new FileStorageValidationException("Uploaded file is not a supported image format.");
				}
			} finally {
				reader.dispose();
			}
		} catch (IOException | IndexOutOfBoundsException | IllegalArgumentException unreadable) {
			throw new FileStorageValidationException("Uploaded file is not a supported image format.");
		}
	}

	private void validateExtension(String originalFilename) {
		int lastDotIndex = originalFilename.lastIndexOf('.');
		if (lastDotIndex <= 0 || lastDotIndex == originalFilename.length() - 1) {
			throw new FileStorageValidationException("Uploaded file must have a supported extension.");
		}
		String extension = originalFilename.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
		if (!extension.equals("jpg") && !extension.equals("jpeg")
				&& !extension.equals("png") && !extension.equals("gif")) {
			throw new FileStorageValidationException("Only JPEG, PNG, and GIF files are supported.");
		}
	}

	public IllustrationImportResult archiveStoredFile(StoredFile storedFile) {
		IllustrationImportResult result;
		try {
			illustrationPersistenceService.findIllustrationIdBySha256(storedFile.sha256())
					.ifPresent(existingIllustrationId -> {
						throw new DuplicateIllustrationException(existingIllustrationId);
					});
			result = illustrationPersistenceService.persist(storedFile);
		} catch (RuntimeException operationException) {
			RuntimeException failure = operationException;
			if (operationException instanceof DuplicateKeyException) {
				failure = resolveConcurrentDuplicate(storedFile.sha256(), operationException);
			}
			cleanupStoredFile(storedFile.storageKey(), failure);
			throw failure;
		}

		generateThumbnailAfterImport(storedFile, result);
		return result;
	}

	private void generateThumbnailAfterImport(StoredFile storedFile, IllustrationImportResult result) {
		if (!"image/jpeg".equals(storedFile.mimeType()) && !"image/png".equals(storedFile.mimeType())) {
			return;
		}

		try {
			thumbnailService.generateThumbnail(storedFile.storageKey());
		} catch (RuntimeException thumbnailException) {
			log.warn(
					"Failed to generate thumbnail after successful import. illustrationId={}, assetId={}, storageKey={}",
					result.illustrationId(),
					result.assetId(),
					storedFile.storageKey(),
					thumbnailException
			);
		}
	}

	private RuntimeException resolveConcurrentDuplicate(String sha256, RuntimeException originalException) {
		try {
			var existingIllustrationId = illustrationPersistenceService.findIllustrationIdBySha256(sha256);
			if (existingIllustrationId.isPresent()) {
				return new DuplicateIllustrationException(existingIllustrationId.get());
			}
			return originalException;
		} catch (RuntimeException lookupException) {
			originalException.addSuppressed(lookupException);
			return originalException;
		}
	}

	private void cleanupStoredFile(String storageKey, RuntimeException originalException) {
		try {
			fileStorageService.delete(storageKey);
		} catch (RuntimeException cleanupException) {
			originalException.addSuppressed(cleanupException);

			log.error(
					"Failed to delete stored file after import failure. storageKey={}",
					storageKey,
					cleanupException
			);
		}
	}
}
