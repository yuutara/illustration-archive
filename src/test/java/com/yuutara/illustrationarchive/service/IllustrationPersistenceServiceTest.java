package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IllustrationPersistenceServiceTest {

	@Test
	void persistsIllustrationAndAssetUsingStoredFileMetadata() {
		IllustrationRepository illustrationRepository = mock(IllustrationRepository.class);
		AssetRepository assetRepository = mock(AssetRepository.class);
		IllustrationPersistenceService service = new IllustrationPersistenceService(illustrationRepository, assetRepository);
		StoredFile storedFile = new StoredFile(
				"original.jpg",
				"2026-09/example.jpg",
				"image/jpeg",
				1234L,
				"a".repeat(64)
		);

		when(illustrationRepository.insert()).thenReturn(10L);
		when(assetRepository.insert(
				10L,
				"original.jpg",
				"2026-09/example.jpg",
				"image/jpeg",
				1234L,
				0,
				"a".repeat(64)
		)).thenReturn(20L);

		IllustrationImportResult result = service.persist(storedFile);

		assertEquals(10L, result.illustrationId());
		assertEquals(20L, result.assetId());
		verify(illustrationRepository).insert();
		verify(assetRepository).insert(
				10L,
				"original.jpg",
				"2026-09/example.jpg",
				"image/jpeg",
				1234L,
				0,
				"a".repeat(64)
		);
	}

	@Test
	void findsIllustrationIdBySha256ThroughAssetRepository() {
		IllustrationRepository illustrationRepository = mock(IllustrationRepository.class);
		AssetRepository assetRepository = mock(AssetRepository.class);
		IllustrationPersistenceService service = new IllustrationPersistenceService(illustrationRepository, assetRepository);

		when(assetRepository.findIllustrationIdBySha256("a".repeat(64))).thenReturn(java.util.Optional.of(10L));

		assertEquals(java.util.Optional.of(10L), service.findIllustrationIdBySha256("a".repeat(64)));
		verify(assetRepository).findIllustrationIdBySha256("a".repeat(64));
	}

	@Test
	void propagatesDuplicateKeyExceptionFromPersist() {
		IllustrationRepository illustrationRepository = mock(IllustrationRepository.class);
		AssetRepository assetRepository = mock(AssetRepository.class);
		IllustrationPersistenceService service = new IllustrationPersistenceService(illustrationRepository, assetRepository);
		StoredFile storedFile = new StoredFile(
				"original.jpg",
				"2026-09/example.jpg",
				"image/jpeg",
				1234L,
				"a".repeat(64)
		);
		DuplicateKeyException duplicateException = new DuplicateKeyException("duplicate key");

		when(illustrationRepository.insert()).thenReturn(10L);
		when(assetRepository.insert(
				10L,
				"original.jpg",
				"2026-09/example.jpg",
				"image/jpeg",
				1234L,
				0,
				"a".repeat(64)
		)).thenThrow(duplicateException);

		assertThrows(DuplicateKeyException.class, () -> service.persist(storedFile));
		verify(illustrationRepository).insert();
		verify(assetRepository).insert(
				10L,
				"original.jpg",
				"2026-09/example.jpg",
				"image/jpeg",
				1234L,
				0,
				"a".repeat(64)
		);
	}
}
