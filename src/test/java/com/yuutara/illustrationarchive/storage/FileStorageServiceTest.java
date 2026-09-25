package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

	private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
	private static final byte[] JPEG_WITH_BODY_BYTES = {
			(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00,
			0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
	};
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
	void storesValidGif() throws Exception {
		StoredFile storedFile = fileStorageService.store(file("animation.gif", GIF_BYTES));
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		String expectedSha256 = HexFormat.of().formatHex(digest.digest(GIF_BYTES));

		assertEquals("image/gif", storedFile.mimeType());
		assertEquals(GIF_BYTES.length, storedFile.fileSize());
		assertTrue(storedFile.storageKey().endsWith(".gif"));
		assertTrue(Files.isRegularFile(temporaryStorageRoot.resolve(storedFile.storageKey())));
		assertArrayEquals(GIF_BYTES, Files.readAllBytes(temporaryStorageRoot.resolve(storedFile.storageKey())));
		assertEquals(expectedSha256, storedFile.sha256());
	}

	@Test
	void calculatesSha256ForCompleteFileIncludingMagicNumber() {
		StoredFile storedFile = fileStorageService.store(file("hash.jpg", JPEG_WITH_BODY_BYTES));

		assertEquals(
				"b3eea2ea6200fe4a401f2541343a9143b35bf6b10e908182a5b3ce86a8192d2d",
				storedFile.sha256()
		);
	}

	@Test
	void streamStoragePreservesFilenameContentAndSha256() throws IOException {
		StoredFile storedFile = fileStorageService.store("stream.PNG", new ByteArrayInputStream(PNG_BYTES));

		assertEquals("stream.PNG", storedFile.originalFilename());
		assertEquals("image/png", storedFile.mimeType());
		assertEquals(PNG_BYTES.length, storedFile.fileSize());
		assertTrue(storedFile.storageKey().endsWith(".png"));
		assertArrayEquals(PNG_BYTES, Files.readAllBytes(temporaryStorageRoot.resolve(storedFile.storageKey())));
		assertEquals("4c4b6a3be1314ab86138bef4314dde022e600960d8689a2c8f8631802d20dab6", storedFile.sha256());
	}

	@Test
	void streamStorageKeepsExtensionAndMagicNumberValidation() {
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.store("image.webp", new ByteArrayInputStream(JPEG_BYTES)));
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.store("image.png", new ByteArrayInputStream(JPEG_BYTES)));
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.store("image.jpg", new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
	}

	@Test
	void streamStorageRejectsMoreThan50MbOfBytesActuallyReadAndRemovesTemporaryFile() throws IOException {
		InputStream oversizedStream = new SequenceInputStream(
				new ByteArrayInputStream(JPEG_BYTES),
				new InputStream() {
					private long remaining = 50L * 1024 * 1024 + 1 - JPEG_BYTES.length;

					@Override
					public int read() {
						if (remaining-- <= 0) {
							return -1;
						}
						return 0;
					}

					@Override
					public int read(byte[] buffer, int offset, int length) {
						if (remaining == 0) {
							return -1;
						}
						int count = (int) Math.min(remaining, length);
						Arrays.fill(buffer, offset, offset + count, (byte) 0);
						remaining -= count;
						return count;
					}
				}
		);

		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.store("large.jpg", oversizedStream));
		try (var files = Files.walk(temporaryStorageRoot)) {
			assertEquals(0, files.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void calculatesSameSha256ForSameContentWithDifferentFilenames() {
		StoredFile first = fileStorageService.store(file("first.jpg", JPEG_WITH_BODY_BYTES));
		StoredFile second = fileStorageService.store(file("second.jpg", JPEG_WITH_BODY_BYTES));

		assertEquals(first.sha256(), second.sha256());
	}

	@Test
	void calculatesDifferentSha256ForDifferentContent() {
		StoredFile first = fileStorageService.store(file("first.jpg", JPEG_WITH_BODY_BYTES));
		StoredFile second = fileStorageService.store(file("second.jpg", new byte[] {
				(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00,
				0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x09
		}));

		assertNotEquals(first.sha256(), second.sha256());
	}

	@Test
	void calculatesSha256ForExistingFileWithoutChangingIt() throws IOException {
		Path storedPath = temporaryStorageRoot.resolve("2026-09/historical.jpg");
		Files.createDirectories(storedPath.getParent());
		Files.write(storedPath, JPEG_WITH_BODY_BYTES);

		String sha256 = fileStorageService.calculateSha256("2026-09/historical.jpg");

		assertEquals(
				"b3eea2ea6200fe4a401f2541343a9143b35bf6b10e908182a5b3ce86a8192d2d",
				sha256
		);
		assertArrayEquals(JPEG_WITH_BODY_BYTES, Files.readAllBytes(storedPath));
	}

	@Test
	void calculatesSha256ForLargeExistingFileUsingExpectedDigest() throws Exception {
		byte[] content = new byte[1024 * 1024 + 123];
		Arrays.fill(content, (byte) 0x5A);
		Path storedPath = temporaryStorageRoot.resolve("2026-09/large.jpg");
		Files.createDirectories(storedPath.getParent());
		Files.write(storedPath, content);

		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		String expectedSha256 = HexFormat.of().formatHex(digest.digest(content));

		assertEquals(expectedSha256, fileStorageService.calculateSha256("2026-09/large.jpg"));
	}

	@Test
	void rejectsFileLargerThan50MegabytesBeforeOpeningItsStream() {
		MultipartFile oversizedFile = mock(MultipartFile.class);
		when(oversizedFile.isEmpty()).thenReturn(false);
		when(oversizedFile.getOriginalFilename()).thenReturn("large.jpg");
		when(oversizedFile.getSize()).thenReturn(50L * 1024 * 1024 + 1);

		assertThrows(FileStorageValidationException.class, () -> fileStorageService.store(oversizedFile));
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
	void rejectsUnsupportedMultipartExtensionBeforeOpeningStream() throws IOException {
		MultipartFile file = mock(MultipartFile.class);
		when(file.isEmpty()).thenReturn(false);
		when(file.getOriginalFilename()).thenReturn("not-supported.webp");

		assertThrows(FileStorageValidationException.class, () -> fileStorageService.store(file));
		verify(file, never()).getInputStream();
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

	@Test
	void loadsStoredFileAsResource() throws IOException {
		Path storedPath = temporaryStorageRoot.resolve("2026-09/image.jpg");
		Files.createDirectories(storedPath.getParent());
		Files.write(storedPath, JPEG_BYTES);

		Resource resource = fileStorageService.load("2026-09/image.jpg");

		assertTrue(resource.exists());
		assertEquals(storedPath.toRealPath(), resource.getFile().toPath().toRealPath());
	}

	@Test
	void rejectsNullOrBlankStorageKeyWhenLoading() {
		assertThrows(FileStorageValidationException.class, () -> fileStorageService.load(null));
		assertThrows(FileStorageValidationException.class, () -> fileStorageService.load("  "));
	}

	@Test
	void rejectsAbsoluteStorageKeyWhenLoading() {
		String absolutePath = temporaryStorageRoot.resolve("outside.jpg").toAbsolutePath().toString();

		assertThrows(FileStorageValidationException.class, () -> fileStorageService.load(absolutePath));
	}

	@Test
	void rejectsStorageKeyThatEscapesStorageRootWhenLoading() {
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.load("../outside-storage-root.jpg"));
	}

	@Test
	void rejectsStorageKeyThatEscapesStorageRootWhenCalculatingSha256() {
		assertThrows(FileStorageValidationException.class,
				() -> fileStorageService.calculateSha256("../outside-storage-root.jpg"));
	}

	@Test
	void failsWhenLoadingMissingFile() {
		assertThrows(FileStorageException.class, () -> fileStorageService.load("2026-09/missing.jpg"));
	}

	@Test
	void failsWhenCalculatingSha256ForMissingFile() {
		assertThrows(FileStorageException.class,
				() -> fileStorageService.calculateSha256("2026-09/missing.jpg"));
	}

	private MockMultipartFile file(String filename, byte[] content) {
		return new MockMultipartFile("file", filename, "application/octet-stream", content);
	}
}
