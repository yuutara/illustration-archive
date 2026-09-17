package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IllustrationImportService {

	private final FileStorageService fileStorageService;
	private final IllustrationPersistenceService illustrationPersistenceService;

	public IllustrationImportService(
			FileStorageService fileStorageService,
			IllustrationPersistenceService illustrationPersistenceService
	) {
		this.fileStorageService = fileStorageService;
		this.illustrationPersistenceService = illustrationPersistenceService;
	}

	public IllustrationImportResult importSingle(MultipartFile file) {
		StoredFile storedFile = fileStorageService.store(file);

		try {
			return illustrationPersistenceService.persist(storedFile);
		} catch (RuntimeException persistenceException) {
			try {
				fileStorageService.delete(storedFile.storageKey());
			} catch (RuntimeException cleanupException) {
				persistenceException.addSuppressed(cleanupException);
			}
			throw persistenceException;
		}
	}
}
