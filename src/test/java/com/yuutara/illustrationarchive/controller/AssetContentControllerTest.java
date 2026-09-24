package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.AssetContent;
import com.yuutara.illustrationarchive.service.AssetContentService;
import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetContentControllerTest {
	@TempDir
	Path storageRoot;

	@Test
	void returnsAssetResourceWithContentHeaders() throws Exception {
		AssetContentService assetContentService = mock(AssetContentService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new AssetContentController(assetContentService))
				.build();
		byte[] imageBytes = {1, 2, 3, 4};
		when(assetContentService.load(3L)).thenReturn(new AssetContent(
				new ByteArrayResource(imageBytes),
				"image/jpeg",
				imageBytes.length
		));

		mockMvc.perform(get("/api/assets/3/content"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("image/jpeg"))
				.andExpect(header().string("Content-Length", String.valueOf(imageBytes.length)))
				.andExpect(content().bytes(imageBytes));

		verify(assetContentService).load(3L);
	}

	@Test
	void returnsJpgAndJpegThumbnailBytes() throws Exception {
		assertThumbnail(10L, "first.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
		assertThumbnail(11L, "second.jpeg", "image/jpeg", new byte[] {5, 6, 7, 8});
	}

	@Test
	void returnsPngThumbnailBytes() throws Exception {
		assertThumbnail(12L, "image.png", "image/png", new byte[] {9, 8, 7, 6});
	}

	@Test
	void returnsNotFoundForUnknownAsset() throws Exception {
		AssetRepository repository = mock(AssetRepository.class);
		when(repository.findContentInfoById(99L)).thenReturn(Optional.empty());

		mvc(repository).perform(get("/api/assets/99/thumbnail"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundWithoutGeneratingMissingThumbnail() throws Exception {
		String storageKey = "2026-09/missing.jpg";
		AssetRepository repository = repository(13L, storageKey, "image/jpeg", 20);

		mvc(repository).perform(get("/api/assets/13/thumbnail"))
				.andExpect(status().isNotFound());
		org.junit.jupiter.api.Assertions.assertFalse(Files.exists(storageRoot.resolve("thumbnails").resolve(storageKey)));
	}

	@Test
	void gifHasNoThumbnailButContentRemainsAvailable() throws Exception {
		String storageKey = "2026-09/animation.gif";
		byte[] originalBytes = {1, 3, 5};
		write(storageKey, originalBytes);
		AssetRepository repository = repository(14L, storageKey, "image/gif", originalBytes.length);
		MockMvc mockMvc = mvc(repository);

		mockMvc.perform(get("/api/assets/14/thumbnail"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/assets/14/content"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("image/gif"))
				.andExpect(content().bytes(originalBytes));
	}

	@Test
	void contentStillReturnsOriginalWhenThumbnailExists() throws Exception {
		String storageKey = "2026-09/original.jpg";
		byte[] originalBytes = {1, 2, 3};
		byte[] thumbnailBytes = {7, 8};
		write(storageKey, originalBytes);
		write("thumbnails/" + storageKey, thumbnailBytes);
		AssetRepository repository = repository(15L, storageKey, "image/jpeg", originalBytes.length);

		mvc(repository).perform(get("/api/assets/15/content"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("image/jpeg"))
				.andExpect(content().bytes(originalBytes));
	}

	private void assertThumbnail(long id, String filename, String mimeType, byte[] thumbnailBytes) throws Exception {
		String storageKey = "2026-09/" + filename;
		write("thumbnails/" + storageKey, thumbnailBytes);
		AssetRepository repository = repository(id, storageKey, mimeType, 100);

		mvc(repository).perform(get("/api/assets/{id}/thumbnail", id))
				.andExpect(status().isOk())
				.andExpect(content().contentType(mimeType))
				.andExpect(header().string("Content-Length", String.valueOf(thumbnailBytes.length)))
				.andExpect(content().bytes(thumbnailBytes));
	}

	private AssetRepository repository(long id, String storageKey, String mimeType, long originalSize) {
		AssetRepository repository = mock(AssetRepository.class);
		when(repository.findContentInfoById(id)).thenReturn(Optional.of(
				new AssetContentInfo(storageKey, mimeType, originalSize)));
		return repository;
	}

	private MockMvc mvc(AssetRepository repository) {
		FileStorageService fileStorageService = new FileStorageService(storageRoot.toString());
		ThumbnailService thumbnailService = new ThumbnailService(fileStorageService);
		AssetContentService assetContentService = new AssetContentService(repository, fileStorageService, thumbnailService);
		return MockMvcBuilders.standaloneSetup(new AssetContentController(assetContentService)).build();
	}

	private void write(String storageKey, byte[] bytes) throws Exception {
		Path file = storageRoot.resolve(storageKey);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}
}
