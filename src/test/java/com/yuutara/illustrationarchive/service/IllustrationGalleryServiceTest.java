package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import com.yuutara.illustrationarchive.dto.GalleryAsset;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IllustrationGalleryServiceTest {
    @Test
    void resolvesMultiSelectLabelsAndPreservesUnknownIdsWithoutExtraRows() {
        var repository = mock(IllustrationRepository.class);
        var authors = mock(AuthorRepository.class);
        var tags = mock(TagRepository.class);
        var service = new IllustrationGalleryService(repository, authors, tags);
        var query = IllustrationGalleryQuery.withIds("q", List.of(2L, 1L, 999L), List.of(5L, 3L));
        when(repository.findGalleryPage(query, 24, 0L)).thenReturn(List.of());
        when(authors.findSummariesByIds(query.authorIds())).thenReturn(List.of(new com.yuutara.illustrationarchive.dto.AuthorSummary(1L, "A", "a")));
        when(tags.findSummariesByIds(query.tagIds())).thenReturn(List.of(new TagSummary(3L, "T")));
        var filters = service.getGallery(0, 24, query).filters();
        assertEquals(List.of(1L, 2L, 999L), filters.authorIds());
        assertEquals(List.of(3L, 5L), filters.tagIds());
        assertEquals(1, filters.authors().size()); assertEquals(1, filters.tags().size());
        assertEquals(null, filters.authorId()); assertEquals(null, filters.tagId());
        assertThrows(IllegalArgumentException.class, () -> IllustrationGalleryQuery.withIds(null, List.of(1L, 0L), List.of()));
        assertThrows(IllegalArgumentException.class, () -> IllustrationGalleryQuery.withIds(null, java.util.Collections.nCopies(101, 1L), List.of()));
    }
	private static final IllustrationGalleryQuery EMPTY = new IllustrationGalleryQuery(null, null, null);

	@Test
	void returnsRequestedPageWithRoundedUpTotalPages() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository, mock(AuthorRepository.class), mock(TagRepository.class));
		IllustrationGalleryItem item = new IllustrationGalleryItem(
				10L, "Example", null, 20L, "image/jpeg", 1, LocalDateTime.of(2026, 9, 18, 10, 0), List.of()
		);
		List<GalleryAsset> assets = List.of(new GalleryAsset(20L, "image/jpeg", 0));
		when(repository.count(EMPTY)).thenReturn(53L);
		when(repository.findGalleryPage(EMPTY, 24, 24L)).thenReturn(List.of(item));
		when(repository.findGalleryAssetsByIllustrationIds(List.of(10L))).thenReturn(Map.of(10L, assets));

		var result = service.getGallery(1, 24, EMPTY);

		assertEquals(1, result.page());
		assertEquals(24, result.size());
		assertEquals(53L, result.totalElements());
		assertEquals(3, result.totalPages());
		assertEquals(assets, result.items().get(0).assets());
		verify(repository).count(EMPTY);
		verify(repository).findGalleryPage(EMPTY, 24, 24L);
		verify(repository).findGalleryAssetsByIllustrationIds(List.of(10L));
	}

	@Test
	void returnsZeroTotalPagesWhenThereAreNoIllustrations() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository, mock(AuthorRepository.class), mock(TagRepository.class));
		when(repository.count(EMPTY)).thenReturn(0L);
		when(repository.findGalleryPage(EMPTY, 20, 0L)).thenReturn(List.of());

		var result = service.getGallery(0, 20, EMPTY);

		assertEquals(0L, result.totalElements());
		assertEquals(0, result.totalPages());
		assertEquals(List.of(), result.items());
	}

	@Test
	void rejectsNegativePageWithoutQueryingRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository, mock(AuthorRepository.class), mock(TagRepository.class));

		assertThrows(IllegalArgumentException.class, () -> service.getGallery(-1, 20, EMPTY));

		verifyNoInteractions(repository);
	}

	@Test
	void rejectsSizeBelowOneWithoutQueryingRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository, mock(AuthorRepository.class), mock(TagRepository.class));

		assertThrows(IllegalArgumentException.class, () -> service.getGallery(0, 0, EMPTY));

		verifyNoInteractions(repository);
	}

	@Test
	void rejectsSizeAboveOneHundredWithoutQueryingRepository() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationGalleryService service = new IllustrationGalleryService(repository, mock(AuthorRepository.class), mock(TagRepository.class));

		assertThrows(IllegalArgumentException.class, () -> service.getGallery(0, 101, EMPTY));

		verifyNoInteractions(repository);
	}

	@Test
	void filtersCountPageAndAssetsTogetherAndResolvesLabelsEvenWithoutResults() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		AuthorRepository authors = mock(AuthorRepository.class);
		TagRepository tags = mock(TagRepository.class);
		var service = new IllustrationGalleryService(repository, authors, tags);
		var query = new IllustrationGalleryQuery("  夏日  ", 12L, 7L);
		when(repository.count(query)).thenReturn(0L);
		when(repository.findGalleryPage(query, 24, 48L)).thenReturn(List.of());
		when(authors.findById(12L)).thenReturn(Optional.of(new AuthorDetail(12L, "Artist", "artist", null, null)));
		when(tags.findById(7L)).thenReturn(Optional.of(new TagSummary(7L, "风景")));
		var page = service.getGallery(2, 24, query);
		assertEquals(0, page.totalPages());
		assertEquals("夏日", page.filters().q());
		assertEquals("Artist", page.filters().author().displayName());
		assertEquals("风景", page.filters().tag().name());
		verify(repository).count(query);
		verify(repository).findGalleryPage(query, 24, 48L);
		verify(repository).findGalleryAssetsByIllustrationIds(List.of());
	}

	@Test
	void nonexistentIdsRemainAppliedAndQueryValidationRejectsInvalidInput() {
		var repository = mock(IllustrationRepository.class);
		var authors = mock(AuthorRepository.class);
		var tags = mock(TagRepository.class);
		var service = new IllustrationGalleryService(repository, authors, tags);
		var query = new IllustrationGalleryQuery("  ", 99L, 98L);
		when(repository.findGalleryPage(query, 24, 0L)).thenReturn(List.of());
		var filters = service.getGallery(0, 24, query).filters();
		assertEquals(99L, filters.authorId());
		assertEquals(98L, filters.tagId());
		assertEquals(null, filters.author());
		assertEquals(null, filters.tag());
		assertThrows(IllegalArgumentException.class, () -> new IllustrationGalleryQuery("x".repeat(201), null, null));
		assertThrows(IllegalArgumentException.class, () -> new IllustrationGalleryQuery(null, 0L, null));
		assertThrows(IllegalArgumentException.class, () -> new IllustrationGalleryQuery(null, null, -1L));
	}
}
