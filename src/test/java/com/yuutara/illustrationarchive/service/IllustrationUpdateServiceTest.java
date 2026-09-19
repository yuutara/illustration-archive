package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.repository.IllustrationDetailBase;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IllustrationUpdateServiceTest {

	@Test
	void throwsWhenIllustrationDoesNotExistWithoutUpdating() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationUpdateService service = new IllustrationUpdateService(repository);
		when(repository.findDetailBaseById(99L)).thenReturn(Optional.empty());

		assertThrows(IllustrationNotFoundException.class,
				() -> service.updateBasicMetadata(99L, new IllustrationPatchRequest()));

		verify(repository).findDetailBaseById(99L);
		verify(repository, never()).updateBasicMetadata(anyLong(), any());
	}

	@Test
	void doesNotUpdateWhenNoFieldsArePresent() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationUpdateService service = new IllustrationUpdateService(repository);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, new IllustrationPatchRequest());

		verify(repository).findDetailBaseById(10L);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void passesPresentFieldsAndValuesToRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationUpdateService service = new IllustrationUpdateService(repository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle("New title");
		request.setNote("New note");
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, request);

		verify(repository).updateBasicMetadata(10L, request);
	}

	@Test
	void passesExplicitNullTitleToRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationUpdateService service = new IllustrationUpdateService(repository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle(null);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, request);

		assertTrue(request.titlePresent());
		assertNull(request.title());
		verify(repository).updateBasicMetadata(10L, request);
	}

	private IllustrationDetailBase detailBase() {
		return new IllustrationDetailBase(
				10L,
				null,
				null,
				null,
				null,
				LocalDateTime.of(2026, 9, 19, 17, 0),
				LocalDateTime.of(2026, 9, 19, 17, 0)
		);
	}
}
