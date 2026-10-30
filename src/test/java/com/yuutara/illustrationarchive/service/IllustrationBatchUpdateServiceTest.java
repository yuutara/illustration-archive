package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationBatchUpdateRequest;
import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationBatchRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IllustrationBatchUpdateServiceTest {

	private final IllustrationBatchRepository batch = mock(IllustrationBatchRepository.class);
	private final AuthorRepository authors = mock(AuthorRepository.class);
	private final TagRepository tags = mock(TagRepository.class);
	private final IllustrationBatchUpdateService service = new IllustrationBatchUpdateService(batch, authors, tags);

	private static IllustrationBatchUpdateRequest request(List<Long> ids) {
		IllustrationBatchUpdateRequest request = new IllustrationBatchUpdateRequest();
		request.setIds(ids);
		return request;
	}

	private void assertNoWrites() {
		verify(batch, never()).updateAuthor(anyList(), org.mockito.ArgumentMatchers.any());
		verify(batch, never()).addTags(anyList(), anyList());
		verify(batch, never()).removeTags(anyList(), anyList());
	}

	@Test
	void appliesAuthorThenRemovalsThenAdditionsToDistinctSortedIds() {
		when(batch.findExistingIds(List.of(1L, 2L))).thenReturn(List.of(1L, 2L));
		when(tags.findSummariesByIds(List.of(5L))).thenReturn(List.of(new TagSummary(5L, "海")));
		IllustrationBatchUpdateRequest request = request(List.of(2L, 1L, 2L));
		request.setAuthorId(null);
		request.setAddTagIds(List.of(5L, 5L));
		request.setRemoveTagIds(List.of(6L));

		service.update(request);

		InOrder order = inOrder(batch);
		order.verify(batch).updateAuthor(List.of(1L, 2L), null);
		order.verify(batch).removeTags(List.of(1L, 2L), List.of(6L));
		order.verify(batch).addTags(List.of(1L, 2L), List.of(5L));
	}

	@Test
	void leavesAuthorsAloneWhenTheFieldIsAbsent() {
		when(batch.findExistingIds(List.of(1L))).thenReturn(List.of(1L));
		IllustrationBatchUpdateRequest request = request(List.of(1L));
		request.setRemoveTagIds(List.of(6L));

		service.update(request);

		verify(batch, never()).updateAuthor(anyList(), org.mockito.ArgumentMatchers.any());
		verify(batch).removeTags(List.of(1L), List.of(6L));
	}

	@Test
	void rejectsRequestsThatSelectNothingTooMuchOrChangeNothing() {
		for (List<Long> ids : List.of(List.<Long>of(), Collections.nCopies(IllustrationBatchUpdateService.MAX_WORKS + 1, 1L), List.of(0L), List.of(-1L))) {
			IllustrationBatchUpdateRequest request = request(ids);
			request.setAuthorId(null);
			assertThrows(IllegalArgumentException.class, () -> service.update(request));
		}
		assertThrows(IllegalArgumentException.class, () -> service.update(new IllustrationBatchUpdateRequest()));
		assertThrows(IllegalArgumentException.class, () -> service.update(request(List.of(1L))));

		IllustrationBatchUpdateRequest conflicting = request(List.of(1L));
		conflicting.setAddTagIds(List.of(5L));
		conflicting.setRemoveTagIds(List.of(5L));
		assertThrows(IllegalArgumentException.class, () -> service.update(conflicting));
		assertNoWrites();
	}

	@Test
	void writesNothingWhenAWorkAuthorOrTagIsMissing() {
		when(batch.findExistingIds(List.of(1L, 2L))).thenReturn(List.of(1L));
		IllustrationBatchUpdateRequest missingWork = request(List.of(1L, 2L));
		missingWork.setAuthorId(null);
		assertThrows(IllustrationNotFoundException.class, () -> service.update(missingWork));

		when(batch.findExistingIds(List.of(1L))).thenReturn(List.of(1L));
		when(authors.findById(9L)).thenReturn(Optional.empty());
		IllustrationBatchUpdateRequest missingAuthor = request(List.of(1L));
		missingAuthor.setAuthorId(9L);
		assertThrows(AuthorNotFoundException.class, () -> service.update(missingAuthor));

		when(tags.findSummariesByIds(List.of(5L))).thenReturn(List.of());
		IllustrationBatchUpdateRequest missingTag = request(List.of(1L));
		missingTag.setAddTagIds(List.of(5L));
		assertThrows(TagNotFoundException.class, () -> service.update(missingTag));
		assertNoWrites();
	}
}
