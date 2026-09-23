package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailServiceTest {

	@TempDir
	Path storageRoot;

	private FileStorageService fileStorageService;
	private ThumbnailService thumbnailService;

	@BeforeEach
	void setUp() {
		fileStorageService = new FileStorageService(storageRoot.toString());
		thumbnailService = new ThumbnailService(fileStorageService);
	}

	@Test
	void resizesLandscapeJpegWithoutChangingAspectRatio() throws IOException {
		String storageKey = "2026-09/landscape.jpg";
		writeImage(storageKey, 3000, 2000, "jpeg", Color.BLUE);
		byte[] originalBytes = Files.readAllBytes(storageRoot.resolve(storageKey));

		String thumbnailKey = thumbnailService.generateThumbnail(storageKey);

		assertEquals("thumbnails/" + storageKey, thumbnailKey);
		assertImageDimensions(thumbnailKey, 600, 400);
		assertArrayEquals(originalBytes, Files.readAllBytes(storageRoot.resolve(storageKey)));
	}

	@Test
	void resizesPortraitPngWithoutChangingAspectRatio() throws IOException {
		String storageKey = "2026-09/portrait.png";
		writeImage(storageKey, 1200, 1800, "png", Color.GREEN);

		String thumbnailKey = thumbnailService.generateThumbnail(storageKey);

		assertImageDimensions(thumbnailKey, 400, 600);
	}

	@Test
	void resizesSquareImageToMaximumDimensions() throws IOException {
		String storageKey = "2026-09/square.png";
		writeImage(storageKey, 2508, 2508, "png", Color.RED);

		String thumbnailKey = thumbnailService.generateThumbnail(storageKey);

		assertImageDimensions(thumbnailKey, 600, 600);
	}

	@Test
	void preservesPngTransparencyWhenResizing() throws IOException {
		String storageKey = "2026-09/transparent.png";
		BufferedImage source = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_ARGB);
		for (int y = 300; y < 900; y++) {
			for (int x = 300; x < 900; x++) {
				source.setRGB(x, y, Color.RED.getRGB());
			}
		}
		writeImage(storageKey, source, "png");

		String thumbnailKey = thumbnailService.generateThumbnail(storageKey);
		BufferedImage thumbnail = readImage(thumbnailKey);

		assertTrue(thumbnail.getColorModel().hasAlpha());
		assertEquals(0, thumbnail.getRGB(20, 20) >>> 24);
		assertEquals(255, thumbnail.getRGB(300, 300) >>> 24);
	}

	@Test
	void copiesSmallImageWithoutUpscalingOrReencodingAndKeepsOriginalUnchanged() throws IOException {
		String storageKey = "2026-09/small.png";
		writeImage(storageKey, 320, 200, "png", Color.ORANGE);
		Path sourcePath = storageRoot.resolve(storageKey);
		byte[] originalBytes = Files.readAllBytes(sourcePath);

		String thumbnailKey = thumbnailService.generateThumbnail(storageKey);

		assertEquals("thumbnails/" + storageKey, thumbnailKey);
		assertImageDimensions(thumbnailKey, 320, 200);
		assertArrayEquals(originalBytes, Files.readAllBytes(storageRoot.resolve(thumbnailKey)));
		assertArrayEquals(originalBytes, Files.readAllBytes(sourcePath));
	}

	@Test
	void returnsExistingThumbnailWithoutReadingOrOverwritingSource() throws IOException {
		String storageKey = "2026-09/not-present.jpg";
		String thumbnailKey = "thumbnails/" + storageKey;
		Path thumbnailPath = storageRoot.resolve(thumbnailKey);
		Files.createDirectories(thumbnailPath.getParent());
		byte[] existingThumbnail = "already generated".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(thumbnailPath, existingThumbnail);

		assertEquals(thumbnailKey, thumbnailService.generateThumbnail(storageKey));
		assertArrayEquals(existingThumbnail, Files.readAllBytes(thumbnailPath));
	}

	@Test
	void deletesDerivedThumbnailWithoutDeletingOriginalAndIsIdempotent() throws IOException {
		String storageKey = "2026-09/delete-me.png";
		writeImage(storageKey, 24, 18, "png", Color.MAGENTA);
		Path sourcePath = storageRoot.resolve(storageKey);
		String thumbnailKey = thumbnailService.generateThumbnail(storageKey);
		Path thumbnailPath = storageRoot.resolve(thumbnailKey);

		thumbnailService.deleteThumbnail(storageKey);
		thumbnailService.deleteThumbnail(storageKey);

		assertTrue(Files.isRegularFile(sourcePath));
		assertTrue(Files.notExists(thumbnailPath));
	}

	@Test
	void rejectsParentTraversalAndAbsoluteStorageKeys() {
		assertThrows(
				FileStorageValidationException.class,
				() -> thumbnailService.generateThumbnail("nested/../outside.png")
		);
		assertThrows(
				FileStorageValidationException.class,
				() -> thumbnailService.generateThumbnail(storageRoot.resolve("outside.png").toString())
		);
		assertThrows(
				FileStorageValidationException.class,
				() -> thumbnailService.deleteThumbnail("nested/../outside.png")
		);
		assertThrows(
				FileStorageValidationException.class,
				() -> thumbnailService.deleteThumbnail(storageRoot.resolve("outside.png").toString())
		);
	}

	@Test
	void missingOriginalFileFailsClearly() {
		assertThrows(
				FileStorageException.class,
				() -> thumbnailService.generateThumbnail("2026-09/missing.jpg")
		);
	}

	@Test
	void doesNotGenerateThumbnailForGif() throws IOException {
		String storageKey = "2026-09/animated.gif";
		Path sourcePath = storageRoot.resolve(storageKey);
		Files.createDirectories(sourcePath.getParent());
		BufferedImage gifImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		assertTrue(ImageIO.write(gifImage, "gif", sourcePath.toFile()));

		assertThrows(
				FileStorageValidationException.class,
				() -> thumbnailService.generateThumbnail(storageKey)
		);
		assertTrue(Files.exists(sourcePath));
		assertTrue(Files.notExists(storageRoot.resolve("thumbnails").resolve(storageKey)));
	}

	@Test
	void decodeFailureDoesNotLeaveTemporaryThumbnailFile() throws IOException {
		String storageKey = "2026-09/corrupt.jpg";
		Path sourcePath = storageRoot.resolve(storageKey);
		Files.createDirectories(sourcePath.getParent());
		Files.write(sourcePath, new byte[]{1, 2, 3, 4});

		assertThrows(ThumbnailGenerationException.class, () -> thumbnailService.generateThumbnail(storageKey));
		Path thumbnailDirectory = storageRoot.resolve("thumbnails").resolve("2026-09");
		assertTrue(Files.notExists(thumbnailDirectory));
	}

	private void writeImage(String storageKey, int width, int height, String format, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		writeImage(storageKey, image, format);
	}

	private void writeImage(String storageKey, BufferedImage image, String format) throws IOException {
		Path path = storageRoot.resolve(storageKey);
		Files.createDirectories(path.getParent());
		assertTrue(ImageIO.write(image, format, path.toFile()));
	}

	private void assertImageDimensions(String storageKey, int expectedWidth, int expectedHeight) throws IOException {
		BufferedImage image = readImage(storageKey);
		assertEquals(expectedWidth, image.getWidth());
		assertEquals(expectedHeight, image.getHeight());
	}

	private BufferedImage readImage(String storageKey) throws IOException {
		return ImageIO.read(storageRoot.resolve(storageKey).toFile());
	}
}
