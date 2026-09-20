package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IllustrationDeleteServiceTest {

	@Test
	void deletesEveryStoredFileAfterDatabaseDeleteSucceeds() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(databaseDeleteService, fileStorageService);
		when(databaseDeleteService.delete(10L))
				.thenReturn(List.of("2026-09/first.jpg", "2026-09/second.png"));

		service.delete(10L);

		var order = inOrder(databaseDeleteService, fileStorageService);
		order.verify(databaseDeleteService).delete(10L);
		order.verify(fileStorageService).delete("2026-09/first.jpg");
		order.verify(fileStorageService).delete("2026-09/second.png");
	}

	@Test
	void continuesDeletingRemainingFilesWhenOneDeleteFails() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(databaseDeleteService, fileStorageService);
		when(databaseDeleteService.delete(10L))
				.thenReturn(List.of("2026-09/first.jpg", "2026-09/second.png", "2026-09/third.gif"));
		doThrow(new FileStorageException("delete failure"))
				.when(fileStorageService).delete("2026-09/second.png");

		assertDoesNotThrow(() -> service.delete(10L));

		verify(fileStorageService).delete("2026-09/first.jpg");
		verify(fileStorageService).delete("2026-09/second.png");
		verify(fileStorageService).delete("2026-09/third.gif");
	}

	@Test
	void doesNotDeleteFilesWhenDatabaseDeleteFails() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(databaseDeleteService, fileStorageService);
		RuntimeException databaseFailure = new IllegalStateException("database failure");
		when(databaseDeleteService.delete(10L)).thenThrow(databaseFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.delete(10L));

		assertSame(databaseFailure, thrown);
		verifyNoInteractions(fileStorageService);
	}
}
