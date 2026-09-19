package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IllustrationGalleryServiceTest {

	@Test
	void returnsRequestedPageWithRoundedUpTotalPages() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository);
		IllustrationGalleryItem item = new IllustrationGalleryItem(
				10L, "Example", null, 20L, 1, LocalDateTime.of(2026, 9, 18, 10, 0)
		);
		when(repository.count()).thenReturn(53L);
		when(repository.findGalleryPage(24, 24L)).thenReturn(List.of(item));

		var result = service.getGallery(1, 24);

		assertEquals(1, result.page());
		assertEquals(24, result.size());
		assertEquals(53L, result.totalElements());
		assertEquals(3, result.totalPages());
		assertEquals(List.of(item), result.items());
		verify(repository).count();
		verify(repository).findGalleryPage(24, 24L);
	}

	@Test
	void returnsZeroTotalPagesWhenThereAreNoIllustrations() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository);
		when(repository.count()).thenReturn(0L);
		when(repository.findGalleryPage(20, 0L)).thenReturn(List.of());

		var result = service.getGallery(0, 20);

		assertEquals(0L, result.totalElements());
		assertEquals(0, result.totalPages());
		assertEquals(List.of(), result.items());
	}

	@Test
	void rejectsNegativePageWithoutQueryingRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository);

		assertThrows(IllegalArgumentException.class, () -> service.getGallery(-1, 20));

		verifyNoInteractions(repository);
	}

	@Test
	void rejectsSizeBelowOneWithoutQueryingRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository);

		assertThrows(IllegalArgumentException.class, () -> service.getGallery(0, 0));

		verifyNoInteractions(repository);
	}

	@Test
	void rejectsSizeAboveOneHundredWithoutQueryingRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository);

		assertThrows(IllegalArgumentException.class, () -> service.getGallery(0, 101));

		verifyNoInteractions(repository);
	}
}
