package com.yuutara.illustrationarchive.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageContractTest {
	@TempDir
	Path storageRoot;

	@Test
	void springInjectsLocalImplementationsByStorageContract() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"storageTest", Map.of("illustration-archive.storage.root-dir", storageRoot.toString())));
			context.register(FileStorageService.class, ThumbnailService.class);
			context.refresh();
			assertInstanceOf(FileStorageService.class, context.getBean(FileStorage.class));
			assertInstanceOf(ThumbnailService.class, context.getBean(ThumbnailStorage.class));
		}
	}

	@Test
	void originalAndThumbnailOperationsWorkThroughStorageContracts() throws Exception {
		FileStorageService localOriginals = new FileStorageService(storageRoot.toString());
		FileStorage originals = localOriginals;
		ThumbnailStorage thumbnails = new ThumbnailService(localOriginals);
		BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "png", output));
		byte[] bytes = output.toByteArray();

		StoredFile stored = originals.store("original.png", new ByteArrayInputStream(bytes));
		assertTrue(stored.storageKey().matches("\\d{4}-\\d{2}/[0-9a-f]{32}\\.png"));
		try (var input = originals.load(stored.storageKey()).getInputStream()) {
			assertArrayEquals(bytes, input.readAllBytes());
		}
		assertEquals(stored.sha256(), originals.calculateSha256(stored.storageKey()));

		String thumbnailKey = thumbnails.generateThumbnail(stored.storageKey());
		assertEquals("thumbnails/" + stored.storageKey(), thumbnailKey);
		try (var input = thumbnails.loadThumbnail(stored.storageKey()).getInputStream()) {
			assertArrayEquals(bytes, input.readAllBytes());
		}
		assertEquals(thumbnailKey, thumbnails.generateThumbnail(stored.storageKey()));

		thumbnails.deleteThumbnail(stored.storageKey());
		originals.delete(stored.storageKey());
		assertFalse(Files.exists(storageRoot.resolve(thumbnailKey)));
		assertFalse(Files.exists(storageRoot.resolve(stored.storageKey())));
	}
}
