package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IllustrationBatchImportServiceTest {

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
		assertEquals(0, result.failureCount());
		assertEquals(List.of("first.jpg", "second.png", "third.gif"),
				result.items().stream().map(IllustrationBatchImportItemResult::filename).toList());
		assertEquals(List.of(10L, 20L, 30L),
				result.items().stream().map(IllustrationBatchImportItemResult::illustrationId).toList());
		assertEquals(List.of(true, true, true),
				result.items().stream().map(IllustrationBatchImportItemResult::success).toList());

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
		assertEquals(1, result.failureCount());
		assertEquals(10L, result.items().get(0).illustrationId());
		assertEquals("INVALID_FILE", result.items().get(1).errorCode());
		assertEquals("Unsupported image format.", result.items().get(1).message());
		assertNull(result.items().get(1).illustrationId());
		assertEquals(30L, result.items().get(2).illustrationId());
		verify(importService).importSingle(last);
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

		assertEquals("IMPORT_FAILED", result.items().get(0).errorCode());
		assertEquals("Failed to import illustration.", result.items().get(0).message());
		assertEquals(20L, result.items().get(1).illustrationId());
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
