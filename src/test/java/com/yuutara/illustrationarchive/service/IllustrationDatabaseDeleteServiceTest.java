package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.IllustrationDetailBase;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IllustrationDatabaseDeleteServiceTest {

	@Test
	void throwsWhenIllustrationDoesNotExistWithoutDeleting() {
		IllustrationRepository illustrationRepository = mock(IllustrationRepository.class);
		AssetRepository assetRepository = mock(AssetRepository.class);
		IllustrationDatabaseDeleteService service =
				new IllustrationDatabaseDeleteService(illustrationRepository, assetRepository);
		when(illustrationRepository.findDetailBaseById(99L)).thenReturn(Optional.empty());

		assertThrows(IllustrationNotFoundException.class, () -> service.delete(99L));

		verify(illustrationRepository).findDetailBaseById(99L);
		verifyNoMoreInteractions(illustrationRepository);
		verifyNoInteractions(assetRepository);
	}

	@Test
	void deletesDatabaseRecordAndReturnsStorageKeysInRepositoryOrder() {
		IllustrationRepository illustrationRepository = mock(IllustrationRepository.class);
		AssetRepository assetRepository = mock(AssetRepository.class);
		IllustrationDatabaseDeleteService service =
				new IllustrationDatabaseDeleteService(illustrationRepository, assetRepository);
		List<String> storageKeys = List.of("2026-09/first.jpg", "2026-09/second.png");
		when(illustrationRepository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(assetRepository.findStorageKeysByIllustrationId(10L)).thenReturn(storageKeys);

		List<String> result = service.delete(10L);

		assertEquals(storageKeys, result);
		var order = inOrder(illustrationRepository, assetRepository);
		order.verify(illustrationRepository).findDetailBaseById(10L);
		order.verify(assetRepository).findStorageKeysByIllustrationId(10L);
		order.verify(illustrationRepository).deleteById(10L);
	}

	private IllustrationDetailBase detailBase() {
		return new IllustrationDetailBase(
				10L,
				null,
				null,
				null,
				null,
				LocalDateTime.of(2026, 9, 20, 10, 0),
				LocalDateTime.of(2026, 9, 20, 10, 0)
		);
	}
}
