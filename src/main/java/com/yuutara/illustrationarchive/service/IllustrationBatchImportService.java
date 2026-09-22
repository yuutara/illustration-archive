package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class IllustrationBatchImportService {

	private static final Logger log = LoggerFactory.getLogger(IllustrationBatchImportService.class);

	private final IllustrationImportService illustrationImportService;

	public IllustrationBatchImportService(IllustrationImportService illustrationImportService) {
		this.illustrationImportService = illustrationImportService;
	}

	public IllustrationBatchImportResult importBatch(List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			throw new IllegalArgumentException("At least one file is required.");
		}

		List<IllustrationBatchImportItemResult> items = new ArrayList<>(files.size());
		int successCount = 0;
		int duplicateCount = 0;
		int failureCount = 0;

		for (MultipartFile file : files) {
			String filename = file == null ? null : file.getOriginalFilename();
			try {
				IllustrationImportResult result = illustrationImportService.importSingle(file);
				items.add(new IllustrationBatchImportItemResult(
						filename,
						true,
						result.illustrationId(),
						null,
						null,
						IllustrationBatchImportItemResult.SUCCESS
				));
				successCount++;
			} catch (DuplicateIllustrationException exception) {
				items.add(new IllustrationBatchImportItemResult(
						filename,
						false,
						exception.illustrationId(),
						DuplicateIllustrationException.ERROR_CODE,
						exception.getMessage(),
						IllustrationBatchImportItemResult.DUPLICATE
				));
				duplicateCount++;
			} catch (FileStorageValidationException exception) {
				items.add(new IllustrationBatchImportItemResult(
						filename,
						false,
						null,
						"INVALID_FILE",
						exception.getMessage()
				));
				failureCount++;
			} catch (FileStorageException exception) {
				log.error("Failed to store file during batch import. filename={}", filename, exception);
				items.add(failure(filename, "STORAGE_FAILED", "Failed to store file."));
				failureCount++;
			} catch (RuntimeException exception) {
				log.error("Failed to import illustration during batch import. filename={}", filename, exception);
				items.add(failure(filename, "IMPORT_FAILED", "Failed to import illustration."));
				failureCount++;
			}
		}

		return new IllustrationBatchImportResult(
				files.size(),
				successCount,
				duplicateCount,
				failureCount,
				List.copyOf(items)
		);
	}

	private IllustrationBatchImportItemResult failure(String filename, String errorCode, String message) {
		return new IllustrationBatchImportItemResult(
				filename,
				false,
				null,
				errorCode,
				message,
				IllustrationBatchImportItemResult.FAILED
		);
	}
}
