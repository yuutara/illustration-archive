package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStorageServiceTest {

	private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
	private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
	private static final byte[] GIF_BYTES = {'G', 'I', 'F', '8', '9', 'a'};

	@TempDir
	Path temporaryStorageRoot;

	private FileStorageService fileStorageService;

	@BeforeEach
	void setUp() {
		fileStorageService = new FileStorageService(temporaryStorageRoot.toString());
	}

	@Test
	void storesValidJpegAndPreservesOriginalFilename() throws IOException {
		StoredFile storedFile = fileStorageService.store(file("spring.PNG.JPG", JPEG_BYTES));

		assertEquals("spring.PNG.JPG", storedFile.originalFilename());
		assertEquals("image/jpeg", storedFile.mimeType());
		assertEquals(JPEG_BYTES.length, storedFile.fileSize());
		assertTrue(storedFile.storageKey().matches("\\d{4}-\\d{2}/[0-9a-f]{32}\\.jpg"));
		assertTrue(Files.exists(temporaryStorageRoot.resolve(storedFile.storageKey())));
	}

	@Test
	void storesValidPng() {
		StoredFile storedFile = fileStorageService.store(file("drawing.PNG", PNG_BYTES));

		assertEquals("image/png", storedFile.mimeType());
		assertTrue(storedFile.storageKey().endsWith(".png"));
	}

	@Test
	void storesValidGif() {
		StoredFile storedFile = fileStorageService.store(file("animation.gif", GIF_BYTES));

		assertEquals("image/gif", storedFile.mimeType());
		assertTrue(storedFile.storageKey().endsWith(".gif"));
	}

	@Test
	void rejectsEmptyFile() {
		MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

		assertThrows(FileStorageValidationException.class, () -> fileStorageService.store(emptyFile));
	}

	@Test
	void rejectsUnsupportedExtension() {
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.store(file("not-supported.webp", JPEG_BYTES)));
	}

	@Test
	void rejectsExtensionThatDoesNotMatchContent() {
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.store(file("actually-jpeg.png", JPEG_BYTES)));
	}

	@Test
	void deletesStoredFile() {
		StoredFile storedFile = fileStorageService.store(file("delete-me.jpeg", JPEG_BYTES));
		Path storedPath = temporaryStorageRoot.resolve(storedFile.storageKey());

		fileStorageService.delete(storedFile.storageKey());

		assertFalse(Files.exists(storedPath));
	}

	@Test
	void rejectsStorageKeyThatEscapesStorageRoot() {
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.delete("../outside-storage-root.jpg"));
	}

	private MockMultipartFile file(String filename, byte[] content) {
		return new MockMultipartFile("file", filename, "application/octet-stream", content);
	}
}
