package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IllustrationDeleteService {

	private static final Logger log = LoggerFactory.getLogger(IllustrationDeleteService.class);

	private final IllustrationDatabaseDeleteService databaseDeleteService;
	private final FileStorageService fileStorageService;
	private final ThumbnailService thumbnailService;

	public IllustrationDeleteService(
			IllustrationDatabaseDeleteService databaseDeleteService,
			FileStorageService fileStorageService,
			ThumbnailService thumbnailService
	) {
		this.databaseDeleteService = databaseDeleteService;
		this.fileStorageService = fileStorageService;
		this.thumbnailService = thumbnailService;
	}

	public void delete(long illustrationId) {
		List<String> storageKeys = databaseDeleteService.delete(illustrationId);

		for (String storageKey : storageKeys) {
			try {
				thumbnailService.deleteThumbnail(storageKey);
			} catch (RuntimeException exception) {
				log.error("Failed to delete illustration thumbnail file. storageKey={}", storageKey, exception);
			}

			try {
				fileStorageService.delete(storageKey);
			} catch (RuntimeException exception) {
				log.error("Failed to delete illustration asset file. storageKey={}", storageKey, exception);
			}
		}
	}
}
