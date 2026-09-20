package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TagServiceTest {

	@Test
	void createsTagWithTrimmedNameAndReturnsRepositoryResult() {
		TagRepository repository = mock(TagRepository.class);
		TagService service = new TagService(repository);
		TagSummary tag = new TagSummary(5L, "landscape");
		when(repository.insert("landscape")).thenReturn(5L);
		when(repository.findById(5L)).thenReturn(Optional.of(tag));

		TagSummary result = service.create("  landscape  ");

		assertEquals(tag, result);
		verify(repository).insert("landscape");
		verify(repository).findById(5L);
	}

	@Test
	void rejectsNullOrBlankName() {
		TagRepository repository = mock(TagRepository.class);
		TagService service = new TagService(repository);

		assertThrows(IllegalArgumentException.class, () -> service.create(null));
		assertThrows(IllegalArgumentException.class, () -> service.create("   "));

		verifyNoInteractions(repository);
	}

	@Test
	void delegatesSearchToRepository() {
		TagRepository repository = mock(TagRepository.class);
		TagService service = new TagService(repository);
		List<TagSummary> expected = List.of(new TagSummary(5L, "landscape"));
		when(repository.search("land")).thenReturn(expected);

		List<TagSummary> result = service.search("land");

		assertEquals(expected, result);
		verify(repository).search("land");
	}
}
