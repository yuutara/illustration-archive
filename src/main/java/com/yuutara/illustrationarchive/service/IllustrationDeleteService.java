package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IllustrationDeleteService {

	private static final Logger log = LoggerFactory.getLogger(IllustrationDeleteService.class);

	private final IllustrationDatabaseDeleteService databaseDeleteService;
	private final FileStorageService fileStorageService;

	public IllustrationDeleteService(
			IllustrationDatabaseDeleteService databaseDeleteService,
			FileStorageService fileStorageService
	) {
		this.databaseDeleteService = databaseDeleteService;
		this.fileStorageService = fileStorageService;
	}

	public void delete(long illustrationId) {
		List<String> storageKeys = databaseDeleteService.delete(illustrationId);

		for (String storageKey : storageKeys) {
			try {
				fileStorageService.delete(storageKey);
			} catch (RuntimeException exception) {
				log.error("Failed to delete illustration asset file. storageKey={}", storageKey, exception);
			}
		}
	}
}
