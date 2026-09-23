package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IllustrationDeleteServiceTest {

	@TempDir
	Path storageRoot;

	@Test
	void deletesEveryStoredFileAfterDatabaseDeleteSucceeds() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(
				databaseDeleteService, fileStorageService, thumbnailService);
		when(databaseDeleteService.delete(10L))
				.thenReturn(List.of("2026-09/first.jpg", "2026-09/second.png"));

		service.delete(10L);

		var order = inOrder(databaseDeleteService, thumbnailService, fileStorageService);
		order.verify(databaseDeleteService).delete(10L);
		order.verify(thumbnailService).deleteThumbnail("2026-09/first.jpg");
		order.verify(fileStorageService).delete("2026-09/first.jpg");
		order.verify(thumbnailService).deleteThumbnail("2026-09/second.png");
		order.verify(fileStorageService).delete("2026-09/second.png");
	}

	@Test
	void deletesOriginalAndThumbnailFilesUsingStorageServices() throws IOException {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = new FileStorageService(storageRoot.toString());
		ThumbnailService thumbnailService = new ThumbnailService(fileStorageService);
		IllustrationDeleteService service = new IllustrationDeleteService(
				databaseDeleteService, fileStorageService, thumbnailService);
		String jpegKey = "2026-09/first.jpg";
		String pngKey = "2026-09/second.png";
		String gifKey = "2026-09/third.gif";
		writeFile(jpegKey, "jpeg original");
		writeFile("thumbnails/" + jpegKey, "jpeg thumbnail");
		writeFile(pngKey, "png original");
		writeFile("thumbnails/" + pngKey, "png thumbnail");
		writeFile(gifKey, "gif original");
		when(databaseDeleteService.delete(10L)).thenReturn(List.of(jpegKey, pngKey, gifKey));

		service.delete(10L);

		assertTrue(Files.notExists(storageRoot.resolve(jpegKey)));
		assertTrue(Files.notExists(storageRoot.resolve("thumbnails/" + jpegKey)));
		assertTrue(Files.notExists(storageRoot.resolve(pngKey)));
		assertTrue(Files.notExists(storageRoot.resolve("thumbnails/" + pngKey)));
		assertTrue(Files.notExists(storageRoot.resolve(gifKey)));
		assertTrue(Files.notExists(storageRoot.resolve("thumbnails/" + gifKey)));
	}

	@Test
	void continuesDeletingRemainingFilesWhenOneDeleteFails() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(
				databaseDeleteService, fileStorageService, thumbnailService);
		when(databaseDeleteService.delete(10L))
				.thenReturn(List.of("2026-09/first.jpg", "2026-09/second.png", "2026-09/third.gif"));
		doThrow(new FileStorageException("delete failure"))
				.when(fileStorageService).delete("2026-09/second.png");

		assertDoesNotThrow(() -> service.delete(10L));

		verify(fileStorageService).delete("2026-09/first.jpg");
		verify(fileStorageService).delete("2026-09/second.png");
		verify(fileStorageService).delete("2026-09/third.gif");
		verify(thumbnailService).deleteThumbnail("2026-09/first.jpg");
		verify(thumbnailService).deleteThumbnail("2026-09/second.png");
		verify(thumbnailService).deleteThumbnail("2026-09/third.gif");
	}

	@Test
	void continuesDeletingOriginalAndRemainingAssetsWhenThumbnailDeleteFails() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(
				databaseDeleteService, fileStorageService, thumbnailService);
		when(databaseDeleteService.delete(10L))
				.thenReturn(List.of("2026-09/first.jpg", "2026-09/second.png"));
		doThrow(new FileStorageException("delete failure"))
				.when(thumbnailService).deleteThumbnail("2026-09/first.jpg");

		assertDoesNotThrow(() -> service.delete(10L));

		verify(fileStorageService).delete("2026-09/first.jpg");
		verify(thumbnailService).deleteThumbnail("2026-09/second.png");
		verify(fileStorageService).delete("2026-09/second.png");
	}

	@Test
	void doesNotDeleteFilesWhenDatabaseDeleteFails() {
		IllustrationDatabaseDeleteService databaseDeleteService = mock(IllustrationDatabaseDeleteService.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationDeleteService service = new IllustrationDeleteService(
				databaseDeleteService, fileStorageService, thumbnailService);
		RuntimeException databaseFailure = new IllegalStateException("database failure");
		when(databaseDeleteService.delete(10L)).thenThrow(databaseFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.delete(10L));

		assertSame(databaseFailure, thrown);
		verifyNoInteractions(fileStorageService, thumbnailService);
	}

	private void writeFile(String storageKey, String contents) throws IOException {
		Path path = storageRoot.resolve(storageKey);
		Files.createDirectories(path.getParent());
		Files.writeString(path, contents);
	}
}
