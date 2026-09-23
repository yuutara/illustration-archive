package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

class IllustrationBatchImportServiceTest {

	@Test
	void thumbnailFailureAfterPersistenceKeepsBatchItemSuccessful() {
		FileStorageService fileStorageService = mock(FileStorageService.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService importService = new IllustrationImportService(
				fileStorageService,
				persistenceService,
				thumbnailService
		);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile file = file("original.jpg");
		StoredFile storedFile = new StoredFile(
				"original.jpg", "2026-09/example.jpg", "image/jpeg", 1234L, "a".repeat(64)
		);
		IllustrationImportResult persistedResult = new IllustrationImportResult(10L, 20L);

		when(fileStorageService.store(file)).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenReturn(persistedResult);
		doThrow(new IllegalStateException("thumbnail decode failure"))
				.when(thumbnailService).generateThumbnail(storedFile.storageKey());

		IllustrationBatchImportResult result = batchService.importBatch(List.of(file));

		assertEquals(1, result.successCount());
		assertEquals(0, result.failureCount());
		assertEquals(IllustrationBatchImportItemResult.SUCCESS, result.items().get(0).status());
		assertEquals(persistedResult.illustrationId(), result.items().get(0).illustrationId());
		verify(fileStorageService, org.mockito.Mockito.never()).delete(storedFile.storageKey());
	}

	@Test
	void importsAllFilesInOrderWhenEveryFileSucceeds() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile first = file("first.jpg");
		MultipartFile second = file("second.png");
		MultipartFile third = file("third.gif");

		when(importService.importSingle(first)).thenReturn(new IllustrationImportResult(10L, 100L));
		when(importService.importSingle(second)).thenReturn(new IllustrationImportResult(20L, 200L));
		when(importService.importSingle(third)).thenReturn(new IllustrationImportResult(30L, 300L));

		IllustrationBatchImportResult result = batchService.importBatch(List.of(first, second, third));

		assertEquals(3, result.total());
		assertEquals(3, result.successCount());
		assertEquals(0, result.duplicateCount());
		assertEquals(0, result.failureCount());
		assertEquals(List.of("first.jpg", "second.png", "third.gif"),
				result.items().stream().map(IllustrationBatchImportItemResult::filename).toList());
		assertEquals(List.of(10L, 20L, 30L),
				result.items().stream().map(IllustrationBatchImportItemResult::illustrationId).toList());
		assertEquals(List.of(true, true, true),
				result.items().stream().map(IllustrationBatchImportItemResult::success).toList());
		assertEquals(List.of(
				IllustrationBatchImportItemResult.SUCCESS,
				IllustrationBatchImportItemResult.SUCCESS,
				IllustrationBatchImportItemResult.SUCCESS
		), result.items().stream().map(IllustrationBatchImportItemResult::status).toList());

		var inOrder = inOrder(importService);
		inOrder.verify(importService).importSingle(first);
		inOrder.verify(importService).importSingle(second);
		inOrder.verify(importService).importSingle(third);
	}

	@Test
	void continuesAfterInvalidFileAndRetainsEarlierSuccesses() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile first = file("first.jpg");
		MultipartFile invalid = file("invalid.png");
		MultipartFile last = file("last.gif");

		when(importService.importSingle(first)).thenReturn(new IllustrationImportResult(10L, 100L));
		when(importService.importSingle(invalid)).thenThrow(new FileStorageValidationException("Unsupported image format."));
		when(importService.importSingle(last)).thenReturn(new IllustrationImportResult(30L, 300L));

		IllustrationBatchImportResult result = batchService.importBatch(List.of(first, invalid, last));

		assertEquals(2, result.successCount());
		assertEquals(0, result.duplicateCount());
		assertEquals(1, result.failureCount());
		assertEquals(10L, result.items().get(0).illustrationId());
		assertEquals("INVALID_FILE", result.items().get(1).errorCode());
		assertEquals("Unsupported image format.", result.items().get(1).message());
		assertNull(result.items().get(1).illustrationId());
		assertEquals(IllustrationBatchImportItemResult.FAILED, result.items().get(1).status());
		assertEquals(30L, result.items().get(2).illustrationId());
		assertEquals(IllustrationBatchImportItemResult.SUCCESS, result.items().get(2).status());
		verify(importService).importSingle(last);
	}

	@Test
	void reportsPreexistingImageAsDuplicateAndContinues() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile duplicate = file("duplicate.jpg");
		MultipartFile succeeding = file("succeeding.png");

		when(importService.importSingle(duplicate)).thenThrow(new DuplicateIllustrationException(42L));
		when(importService.importSingle(succeeding)).thenReturn(new IllustrationImportResult(20L, 200L));

		IllustrationBatchImportResult result = batchService.importBatch(List.of(duplicate, succeeding));

		IllustrationBatchImportItemResult duplicateItem = result.items().get(0);
		assertEquals(2, result.total());
		assertEquals(IllustrationBatchImportItemResult.DUPLICATE, duplicateItem.status());
		assertEquals(DuplicateIllustrationException.ERROR_CODE, duplicateItem.errorCode());
		assertEquals(42L, duplicateItem.illustrationId());
		assertEquals(IllustrationBatchImportItemResult.SUCCESS, result.items().get(1).status());
		assertEquals(1, result.successCount());
		assertEquals(1, result.duplicateCount());
		assertEquals(0, result.failureCount());
		assertEquals(result.total(), result.successCount() + result.duplicateCount() + result.failureCount());
		verify(importService).importSingle(succeeding);
	}

	@Test
	void classifiesTwoSameContentFilesBySequentialSingleImportResults() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile first = file("same-first.jpg");
		MultipartFile second = file("same-second.jpg");

		when(importService.importSingle(first)).thenReturn(new IllustrationImportResult(10L, 100L));
		when(importService.importSingle(second)).thenThrow(new DuplicateIllustrationException(10L));

		IllustrationBatchImportResult result = batchService.importBatch(List.of(first, second));

		assertEquals(List.of(
				IllustrationBatchImportItemResult.SUCCESS,
				IllustrationBatchImportItemResult.DUPLICATE
		), result.items().stream().map(IllustrationBatchImportItemResult::status).toList());
		assertEquals(10L, result.items().get(1).illustrationId());
		assertEquals(1, result.successCount());
		assertEquals(1, result.duplicateCount());
		assertEquals(0, result.failureCount());
	}

	@Test
	void processesSuccessDuplicateAndFailedItemsInInputOrder() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile success = file("success.jpg");
		MultipartFile duplicate = file("duplicate.jpg");
		MultipartFile failed = file("failed.jpg");
		MultipartFile lastSuccess = file("last-success.png");

		when(importService.importSingle(success)).thenReturn(new IllustrationImportResult(10L, 100L));
		when(importService.importSingle(duplicate)).thenThrow(new DuplicateIllustrationException(42L));
		when(importService.importSingle(failed)).thenThrow(new IllegalStateException("database failure"));
		when(importService.importSingle(lastSuccess)).thenReturn(new IllustrationImportResult(30L, 300L));

		IllustrationBatchImportResult result = batchService.importBatch(
				List.of(success, duplicate, failed, lastSuccess)
		);

		assertEquals(4, result.total());
		assertEquals(List.of(
				IllustrationBatchImportItemResult.SUCCESS,
				IllustrationBatchImportItemResult.DUPLICATE,
				IllustrationBatchImportItemResult.FAILED,
				IllustrationBatchImportItemResult.SUCCESS
		), result.items().stream().map(IllustrationBatchImportItemResult::status).toList());
		assertEquals(10L, result.items().get(0).illustrationId());
		assertEquals(42L, result.items().get(1).illustrationId());
		assertNull(result.items().get(2).illustrationId());
		assertEquals(30L, result.items().get(3).illustrationId());
		assertEquals(2, result.successCount());
		assertEquals(1, result.duplicateCount());
		assertEquals(1, result.failureCount());
		assertEquals(result.total(), result.successCount() + result.duplicateCount() + result.failureCount());
		verify(importService).importSingle(lastSuccess);
	}

	@Test
	void reportsStorageFailureWithoutLeakingInternalMessage() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile file = file("broken.jpg");

		when(importService.importSingle(file))
				.thenThrow(new FileStorageException("D:/private-storage/internal-path", new RuntimeException()));

		IllustrationBatchImportResult result = batchService.importBatch(List.of(file));
		IllustrationBatchImportItemResult item = result.items().get(0);

		assertEquals("STORAGE_FAILED", item.errorCode());
		assertEquals("Failed to store file.", item.message());
		assertEquals(IllustrationBatchImportItemResult.FAILED, item.status());
		assertEquals(0, result.successCount());
		assertEquals(0, result.duplicateCount());
		assertEquals(1, result.failureCount());
	}

	@Test
	void reportsImportFailureAndContinuesWithLaterFiles() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);
		MultipartFile failing = file("failing.jpg");
		MultipartFile succeeding = file("succeeding.png");

		when(importService.importSingle(failing)).thenThrow(new IllegalStateException("database details"));
		when(importService.importSingle(succeeding)).thenReturn(new IllustrationImportResult(20L, 200L));

		IllustrationBatchImportResult result = batchService.importBatch(List.of(failing, succeeding));

		assertEquals(2, result.total());
		assertEquals("IMPORT_FAILED", result.items().get(0).errorCode());
		assertEquals("Failed to import illustration.", result.items().get(0).message());
		assertEquals(IllustrationBatchImportItemResult.FAILED, result.items().get(0).status());
		assertEquals(20L, result.items().get(1).illustrationId());
		assertEquals(1, result.successCount());
		assertEquals(0, result.duplicateCount());
		assertEquals(1, result.failureCount());
		assertEquals(result.total(), result.successCount() + result.duplicateCount() + result.failureCount());
		verify(importService).importSingle(succeeding);
	}

	@Test
	void rejectsNullOrEmptyFileListsWithoutCallingImportService() {
		IllustrationImportService importService = mock(IllustrationImportService.class);
		IllustrationBatchImportService batchService = new IllustrationBatchImportService(importService);

		assertThrows(IllegalArgumentException.class, () -> batchService.importBatch(null));
		assertThrows(IllegalArgumentException.class, () -> batchService.importBatch(List.of()));

		verifyNoInteractions(importService);
	}

	private MultipartFile file(String filename) {
		MultipartFile file = mock(MultipartFile.class);
		when(file.getOriginalFilename()).thenReturn(filename);
		return file;
	}
}
