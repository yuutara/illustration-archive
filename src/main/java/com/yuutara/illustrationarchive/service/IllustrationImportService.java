package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IllustrationImportService {

	private final FileStorageService fileStorageService;
	private final IllustrationPersistenceService illustrationPersistenceService;
	private final ThumbnailService thumbnailService;
	private static final Logger log =
			LoggerFactory.getLogger(IllustrationImportService.class);

	public IllustrationImportService(
			FileStorageService fileStorageService,
			IllustrationPersistenceService illustrationPersistenceService,
			ThumbnailService thumbnailService
	) {
		this.fileStorageService = fileStorageService;
		this.illustrationPersistenceService = illustrationPersistenceService;
		this.thumbnailService = thumbnailService;
	}

	public IllustrationImportResult importSingle(MultipartFile file) {
		StoredFile storedFile = fileStorageService.store(file);
		return archiveStoredFile(storedFile);
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
