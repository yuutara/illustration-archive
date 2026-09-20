package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationDetailBase;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.repository.IllustrationTagRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IllustrationUpdateServiceTest {

	@Test
	void throwsWhenIllustrationDoesNotExistWithoutUpdating() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		when(repository.findDetailBaseById(99L)).thenReturn(Optional.empty());

		assertThrows(IllustrationNotFoundException.class,
				() -> service.updateBasicMetadata(99L, new IllustrationPatchRequest()));

		verify(repository).findDetailBaseById(99L);
		verify(repository, never()).updateBasicMetadata(anyLong(), any());
		verifyNoInteractions(authorRepository, tagRepository, illustrationTagRepository);
	}

	@Test
	void doesNotUpdateAnythingWhenNoFieldsArePresent() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, new IllustrationPatchRequest());

		verify(repository).findDetailBaseById(10L);
		verifyNoInteractions(authorRepository, tagRepository, illustrationTagRepository);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void passesPresentFieldsAndValuesToRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle("New title");
		request.setNote("New note");
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, request);

		verify(repository).updateBasicMetadata(10L, request);
		verifyNoInteractions(tagRepository, illustrationTagRepository);
	}

	@Test
	void passesExplicitNullTitleToRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle(null);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, request);

		assertTrue(request.titlePresent());
		assertNull(request.title());
		verify(repository).updateBasicMetadata(10L, request);
	}

	@Test
	void validatesExistingAuthorBeforeUpdating() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setAuthorId(1L);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(authorRepository.findById(1L))
				.thenReturn(Optional.of(new AuthorDetail(1L, "Artist", null, null, null)));

		service.updateBasicMetadata(10L, request);

		verify(authorRepository).findById(1L);
		verify(repository).updateBasicMetadata(10L, request);
		verifyNoInteractions(tagRepository, illustrationTagRepository);
	}

	@Test
	void throwsWhenAuthorDoesNotExistWithoutUpdating() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setAuthorId(999L);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(authorRepository.findById(999L)).thenReturn(Optional.empty());

		assertThrows(AuthorNotFoundException.class, () -> service.updateBasicMetadata(10L, request));

		verify(authorRepository).findById(999L);
		verify(repository, never()).updateBasicMetadata(anyLong(), any());
		verifyNoInteractions(tagRepository, illustrationTagRepository);
	}

	@Test
	void clearsAuthorWithoutQueryingAuthorRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setAuthorId(null);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, request);

		verifyNoInteractions(authorRepository, tagRepository, illustrationTagRepository);
		verify(repository).updateBasicMetadata(10L, request);
	}

	@Test
	void missingAuthorIdDoesNotQueryAuthorRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, new IllustrationPatchRequest());

		verifyNoInteractions(authorRepository, tagRepository, illustrationTagRepository);
		verify(repository).findDetailBaseById(10L);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void emptyTagIdsDeleteOldAssociationsWithoutInserting() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTagIds(List.of());
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		service.updateBasicMetadata(10L, request);

		verify(illustrationTagRepository).deleteByIllustrationId(10L);
		verify(illustrationTagRepository, never()).insert(anyLong(), anyLong());
		verifyNoInteractions(tagRepository);
	}

	@Test
	void validatesTagsBeforeDeletingAndInsertsInRequestOrder() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTagIds(List.of(1L, 2L));
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(tagRepository.findById(1L)).thenReturn(Optional.of(new TagSummary(1L, "one")));
		when(tagRepository.findById(2L)).thenReturn(Optional.of(new TagSummary(2L, "two")));

		service.updateBasicMetadata(10L, request);

		var order = inOrder(tagRepository, illustrationTagRepository);
		order.verify(tagRepository).findById(1L);
		order.verify(tagRepository).findById(2L);
		order.verify(illustrationTagRepository).deleteByIllustrationId(10L);
		order.verify(illustrationTagRepository).insert(10L, 1L);
		order.verify(illustrationTagRepository).insert(10L, 2L);
	}

	@Test
	void duplicateTagIdsAreValidatedAndInsertedOnlyOnce() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTagIds(List.of(1L, 1L, 2L));
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(tagRepository.findById(1L)).thenReturn(Optional.of(new TagSummary(1L, "one")));
		when(tagRepository.findById(2L)).thenReturn(Optional.of(new TagSummary(2L, "two")));

		service.updateBasicMetadata(10L, request);

		verify(tagRepository).findById(1L);
		verify(tagRepository).findById(2L);
		verify(illustrationTagRepository).insert(10L, 1L);
		verify(illustrationTagRepository).insert(10L, 2L);
	}

	@Test
	void missingTagFailsBeforeDeletingOldAssociations() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTagIds(List.of(1L, 999L));
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(tagRepository.findById(1L)).thenReturn(Optional.of(new TagSummary(1L, "one")));
		when(tagRepository.findById(999L)).thenReturn(Optional.empty());

		assertThrows(TagNotFoundException.class, () -> service.updateBasicMetadata(10L, request));

		verify(illustrationTagRepository, never()).deleteByIllustrationId(anyLong());
		verify(illustrationTagRepository, never()).insert(anyLong(), anyLong());
		verify(repository, never()).updateBasicMetadata(anyLong(), any());
	}

	@Test
	void nullTagIdsAreRejectedBeforeDeletingOldAssociations() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTagIds(null);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		assertThrows(IllegalArgumentException.class, () -> service.updateBasicMetadata(10L, request));

		verifyNoInteractions(tagRepository, illustrationTagRepository);
		verify(repository, never()).updateBasicMetadata(anyLong(), any());
	}

	@Test
	void nullTagElementIsRejectedBeforeDeletingOldAssociations() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTagIds(Arrays.asList(1L, null));
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		assertThrows(IllegalArgumentException.class, () -> service.updateBasicMetadata(10L, request));

		verifyNoInteractions(tagRepository, illustrationTagRepository);
	}

	@Test
	void nonPositiveTagIdIsRejectedBeforeDeletingOldAssociations() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));

		IllustrationPatchRequest zeroRequest = new IllustrationPatchRequest();
		zeroRequest.setTagIds(List.of(0L));
		assertThrows(IllegalArgumentException.class, () -> service.updateBasicMetadata(10L, zeroRequest));

		IllustrationPatchRequest negativeRequest = new IllustrationPatchRequest();
		negativeRequest.setTagIds(List.of(-1L));
		assertThrows(IllegalArgumentException.class, () -> service.updateBasicMetadata(10L, negativeRequest));

		verifyNoInteractions(tagRepository, illustrationTagRepository);
	}

	@Test
	void updatesBasicMetadataAndTagsTogether() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authorRepository = mock(AuthorRepository.class);
		TagRepository tagRepository = mock(TagRepository.class);
		IllustrationTagRepository illustrationTagRepository = mock(IllustrationTagRepository.class);
		IllustrationUpdateService service = service(repository, authorRepository, tagRepository, illustrationTagRepository);
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setTitle("New title");
		request.setTagIds(List.of(1L, 2L));
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(detailBase()));
		when(tagRepository.findById(1L)).thenReturn(Optional.of(new TagSummary(1L, "one")));
		when(tagRepository.findById(2L)).thenReturn(Optional.of(new TagSummary(2L, "two")));

		service.updateBasicMetadata(10L, request);

		verify(repository).updateBasicMetadata(10L, request);
		verify(illustrationTagRepository).deleteByIllustrationId(10L);
		verify(illustrationTagRepository).insert(10L, 1L);
		verify(illustrationTagRepository).insert(10L, 2L);
	}

	private IllustrationUpdateService service(
			IllustrationRepository repository,
			AuthorRepository authorRepository,
			TagRepository tagRepository,
			IllustrationTagRepository illustrationTagRepository
	) {
		return new IllustrationUpdateService(repository, authorRepository, tagRepository, illustrationTagRepository);
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
