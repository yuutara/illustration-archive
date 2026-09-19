package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssetContentServiceTest {

	@Test
	void combinesAssetContentInfoWithStoredFileResource() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		AssetContentService service = new AssetContentService(assetRepository, fileStorageService);
		AssetContentInfo contentInfo = new AssetContentInfo("2026-09/example.jpg", "image/jpeg", 123L);
		Resource resource = mock(Resource.class);
		when(assetRepository.findContentInfoById(10L)).thenReturn(Optional.of(contentInfo));
		when(fileStorageService.load(contentInfo.storageKey())).thenReturn(resource);

		AssetContent content = service.load(10L);

		assertSame(resource, content.resource());
		assertEquals("image/jpeg", content.mimeType());
		assertEquals(123L, content.fileSize());
		verify(assetRepository).findContentInfoById(10L);
		verify(fileStorageService).load("2026-09/example.jpg");
	}

	@Test
	void throwsWhenAssetDoesNotExistWithoutLoadingAFile() {
		AssetRepository assetRepository = mock(AssetRepository.class);
		FileStorageService fileStorageService = mock(FileStorageService.class);
		AssetContentService service = new AssetContentService(assetRepository, fileStorageService);
		when(assetRepository.findContentInfoById(99L)).thenReturn(Optional.empty());

		assertThrows(AssetNotFoundException.class, () -> service.load(99L));

		verify(assetRepository).findContentInfoById(99L);
		verifyNoInteractions(fileStorageService);
	}
}
