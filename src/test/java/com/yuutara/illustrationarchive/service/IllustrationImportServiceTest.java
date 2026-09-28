package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

class IllustrationImportServiceTest {

	@Test
	void returnsResultWhenFileStorageAndPersistenceSucceed() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();
		IllustrationImportResult expectedResult = new IllustrationImportResult(10L, 20L);

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenReturn(expectedResult);

		IllustrationImportResult result = service.importSingle(file);

		assertSame(expectedResult, result);
		verify(fileStorageService).store(eq(file.getOriginalFilename()), any(InputStream.class));
		verify(persistenceService).findIllustrationIdBySha256(storedFile.sha256());
		verify(persistenceService).persist(storedFile);
		verify(thumbnailService).generateThumbnail(storedFile.storageKey());
		verify(fileStorageService, org.mockito.Mockito.never()).delete(storedFile.storageKey());
		var order = inOrder(persistenceService, thumbnailService);
		order.verify(persistenceService).persist(storedFile);
		order.verify(thumbnailService).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void archivesStoredFileWithoutMultipartInput() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		StoredFile storedFile = storedFile();
		IllustrationImportResult expectedResult = new IllustrationImportResult(10L, 20L);

		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenReturn(expectedResult);

		assertSame(expectedResult, service.archiveStoredFile(storedFile));
		verifyNoInteractions(fileStorageService);
		verify(persistenceService).persist(storedFile);
		verify(thumbnailService).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void generatesPngThumbnailAfterPersistenceSucceeds() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile("original.png");
		StoredFile storedFile = new StoredFile("original.png", "2026-09/example.png", "image/png", 1234L, "b".repeat(64));
		IllustrationImportResult expectedResult = new IllustrationImportResult(10L, 20L);

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenReturn(expectedResult);

		assertSame(expectedResult, service.importSingle(file));
		verify(thumbnailService).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void skipsThumbnailGenerationForGif() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile("original.gif");
		StoredFile storedFile = new StoredFile("original.gif", "2026-09/example.gif", "image/gif", 1234L, "c".repeat(64));
		IllustrationImportResult expectedResult = new IllustrationImportResult(10L, 20L);

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenReturn(expectedResult);

		assertSame(expectedResult, service.importSingle(file));
		verify(fileStorageService).store(eq(file.getOriginalFilename()), any(InputStream.class));
		verify(persistenceService).findIllustrationIdBySha256(storedFile.sha256());
		verify(persistenceService).persist(storedFile);
		verify(thumbnailService, never()).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void thumbnailFailureDoesNotFailImportOrDeleteStoredOriginal() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();
		IllustrationImportResult expectedResult = new IllustrationImportResult(10L, 20L);
		RuntimeException thumbnailFailure = new IllegalStateException("decode failure");

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenReturn(expectedResult);
		doThrow(thumbnailFailure).when(thumbnailService).generateThumbnail(storedFile.storageKey());

		assertSame(expectedResult, service.importSingle(file));
		verify(persistenceService).persist(storedFile);
		verify(thumbnailService).generateThumbnail(storedFile.storageKey());
		verify(fileStorageService, never()).delete(storedFile.storageKey());
	}

	@Test
	void deletesStoredFileAndRethrowsOriginalExceptionWhenPersistenceFails() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();
		RuntimeException persistenceFailure = new IllegalStateException("database failure");

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenThrow(persistenceFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.importSingle(file));

		assertSame(persistenceFailure, thrown);
		verify(fileStorageService).delete(storedFile.storageKey());
		verify(thumbnailService, never()).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void doesNotPersistWhenFileStorageFails() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, mock(ThumbnailService.class));
		MultipartFile file = uploadFile();
		RuntimeException storageFailure = new FileStorageValidationException("invalid image");

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenThrow(storageFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.importSingle(file));

		assertSame(storageFailure, thrown);
		verifyNoInteractions(persistenceService);
	}

	@Test
	void rejectsPreexistingSha256AndDeletesNewlyStoredFile() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.of(42L));

		DuplicateIllustrationException thrown = assertThrows(
				DuplicateIllustrationException.class,
				() -> service.importSingle(file)
		);

		assertEquals(42L, thrown.illustrationId());
		verify(persistenceService, never()).persist(storedFile);
		verify(fileStorageService).delete(storedFile.storageKey());
		verify(thumbnailService, never()).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void convertsConcurrentDuplicateKeyExceptionToDuplicateWhenHashLookupFindsExistingIllustration() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		ThumbnailService thumbnailService = mock(ThumbnailService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, thumbnailService);
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256()))
				.thenReturn(Optional.empty(), Optional.of(42L));
		when(persistenceService.persist(storedFile))
				.thenThrow(new DuplicateKeyException("duplicate key"));

		DuplicateIllustrationException thrown = assertThrows(
				DuplicateIllustrationException.class,
				() -> service.importSingle(file)
		);

		assertEquals(42L, thrown.illustrationId());
		verify(persistenceService).persist(storedFile);
		verify(fileStorageService).delete(storedFile.storageKey());
		verify(thumbnailService, never()).generateThumbnail(storedFile.storageKey());
	}

	@Test
	void rethrowsDuplicateKeyExceptionWhenHashLookupFindsNothing() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, mock(ThumbnailService.class));
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();
		DuplicateKeyException duplicateKeyException = new DuplicateKeyException("duplicate key");

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256()))
				.thenReturn(Optional.empty(), Optional.empty());
		when(persistenceService.persist(storedFile)).thenThrow(duplicateKeyException);

		DuplicateKeyException thrown = assertThrows(
				DuplicateKeyException.class,
				() -> service.importSingle(file)
		);

		assertSame(duplicateKeyException, thrown);
		verify(fileStorageService).delete(storedFile.storageKey());
	}

	@Test
	void preservesPersistenceFailureWhenCompensationDeleteAlsoFails() {
		FileStorage fileStorageService = mock(FileStorage.class);
		IllustrationPersistenceService persistenceService = mock(IllustrationPersistenceService.class);
		IllustrationImportService service = new IllustrationImportService(fileStorageService, persistenceService, mock(ThumbnailService.class));
		MultipartFile file = uploadFile();
		StoredFile storedFile = storedFile();
		RuntimeException persistenceFailure = new IllegalStateException("database failure");
		RuntimeException cleanupFailure = new IllegalStateException("cleanup failure");

		when(fileStorageService.store(eq(file.getOriginalFilename()), any(InputStream.class))).thenReturn(storedFile);
		when(persistenceService.findIllustrationIdBySha256(storedFile.sha256())).thenReturn(Optional.empty());
		when(persistenceService.persist(storedFile)).thenThrow(persistenceFailure);
		doThrow(cleanupFailure).when(fileStorageService).delete(storedFile.storageKey());

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.importSingle(file));

		assertSame(persistenceFailure, thrown);
		assertEquals(1, thrown.getSuppressed().length);
		assertSame(cleanupFailure, thrown.getSuppressed()[0]);
	}

	@Test
	void rejectsEmptyMultipartBeforeStorage() {
		FileStorage storage = mock(FileStorage.class);
		IllustrationImportService service = new IllustrationImportService(
				storage, mock(IllustrationPersistenceService.class), mock(ThumbnailService.class));
		MultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

		FileStorageValidationException failure = assertThrows(FileStorageValidationException.class,
				() -> service.importSingle(empty));
		assertEquals("Uploaded file must not be empty.", failure.getMessage());
		verifyNoInteractions(storage);
	}

	@Test
	void rejectsMissingMultipartFilenameBeforeOpeningStream() throws IOException {
		FileStorage storage = mock(FileStorage.class);
		IllustrationImportService service = new IllustrationImportService(
				storage, mock(IllustrationPersistenceService.class), mock(ThumbnailService.class));
		MultipartFile upload = mock(MultipartFile.class);
		when(upload.isEmpty()).thenReturn(false);

		FileStorageValidationException failure = assertThrows(FileStorageValidationException.class,
				() -> service.importSingle(upload));
		assertEquals("Uploaded file must have an original filename.", failure.getMessage());
		verify(upload, never()).getInputStream();
		verifyNoInteractions(storage);
	}

	@Test
	void rejectsOversizedMultipartBeforeOpeningStream() throws IOException {
		FileStorage storage = mock(FileStorage.class);
		IllustrationImportService service = new IllustrationImportService(
				storage, mock(IllustrationPersistenceService.class), mock(ThumbnailService.class));
		MultipartFile upload = mock(MultipartFile.class);
		when(upload.isEmpty()).thenReturn(false);
		when(upload.getOriginalFilename()).thenReturn("large.jpg");
		when(upload.getSize()).thenReturn(50L * 1024 * 1024 + 1);

		FileStorageValidationException failure = assertThrows(FileStorageValidationException.class,
				() -> service.importSingle(upload));
		assertEquals("Uploaded file must not exceed 50 MB.", failure.getMessage());
		verify(upload, never()).getInputStream();
		verifyNoInteractions(storage);
	}

	@Test
	void rejectsUnsupportedMultipartExtensionBeforeOpeningStream() throws IOException {
		FileStorage storage = mock(FileStorage.class);
		IllustrationImportService service = new IllustrationImportService(
				storage, mock(IllustrationPersistenceService.class), mock(ThumbnailService.class));
		MultipartFile upload = mock(MultipartFile.class);
		when(upload.isEmpty()).thenReturn(false);
		when(upload.getOriginalFilename()).thenReturn("unsupported.webp");

		FileStorageValidationException failure = assertThrows(FileStorageValidationException.class,
				() -> service.importSingle(upload));
		assertEquals("Only JPEG, PNG, and GIF files are supported.", failure.getMessage());
		verify(upload, never()).getInputStream();
		verifyNoInteractions(storage);
	}

	@Test
	void wrapsMultipartStreamOpenFailureWithoutCallingStorage() throws IOException {
		FileStorage storage = mock(FileStorage.class);
		IllustrationImportService service = new IllustrationImportService(
				storage, mock(IllustrationPersistenceService.class), mock(ThumbnailService.class));
		MultipartFile upload = mock(MultipartFile.class);
		when(upload.isEmpty()).thenReturn(false);
		when(upload.getOriginalFilename()).thenReturn("original.jpg");
		IOException sourceFailure = new IOException("source unavailable");
		when(upload.getInputStream()).thenThrow(sourceFailure);

		FileStorageException failure = assertThrows(FileStorageException.class,
				() -> service.importSingle(upload));
		assertEquals("Failed to store uploaded file.", failure.getMessage());
		assertSame(sourceFailure, failure.getCause());
		verifyNoInteractions(storage);
	}

	private MultipartFile uploadFile() {
		return uploadFile("original.jpg");
	}

	private MultipartFile uploadFile(String filename) {
		return new MockMultipartFile("file", filename, "application/octet-stream",
				new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
	}

	private StoredFile storedFile() {
		return new StoredFile("original.jpg", "2026-09/example.jpg", "image/jpeg", 1234L, "a".repeat(64));
	}
}
