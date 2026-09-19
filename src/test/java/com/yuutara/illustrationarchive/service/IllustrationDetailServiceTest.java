package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AssetSummary;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.repository.IllustrationDetailBase;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IllustrationDetailServiceTest {

	@Test
	void combinesDetailBaseAssetsAndTags() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationDetailService service = new IllustrationDetailService(repository);
		IllustrationDetailBase base = new IllustrationDetailBase(
				10L,
				"Example",
				new AuthorSummary(3L, "Artist", "artist_x"),
				"https://example.com/source",
				"Note",
				LocalDateTime.of(2026, 9, 19, 10, 0),
				LocalDateTime.of(2026, 9, 19, 11, 0)
		);
		List<AssetSummary> assets = List.of(new AssetSummary(20L, "original.jpg", "image/jpeg", 123L, 0));
		List<TagSummary> tags = List.of(new TagSummary(5L, "landscape"));
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(base));
		when(repository.findAssetSummariesByIllustrationId(10L)).thenReturn(assets);
		when(repository.findTagSummariesByIllustrationId(10L)).thenReturn(tags);

		var detail = service.getDetail(10L);

		assertEquals(10L, detail.id());
		assertEquals("Example", detail.title());
		assertEquals(base.author(), detail.author());
		assertEquals("https://example.com/source", detail.sourceUrl());
		assertEquals("Note", detail.note());
		assertEquals(assets, detail.assets());
		assertEquals(tags, detail.tags());
		assertEquals(base.createdAt(), detail.createdAt());
		assertEquals(base.updatedAt(), detail.updatedAt());
		verify(repository).findDetailBaseById(10L);
		verify(repository).findAssetSummariesByIllustrationId(10L);
		verify(repository).findTagSummariesByIllustrationId(10L);
	}

	@Test
	void throwsWhenIllustrationDoesNotExistWithoutQueryingAssetsOrTags() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationDetailService service = new IllustrationDetailService(repository);
		when(repository.findDetailBaseById(99L)).thenReturn(Optional.empty());

		assertThrows(IllustrationNotFoundException.class, () -> service.getDetail(99L));

		verify(repository).findDetailBaseById(99L);
		verify(repository, never()).findAssetSummariesByIllustrationId(anyLong());
		verify(repository, never()).findTagSummariesByIllustrationId(anyLong());
	}

	@Test
	void returnsEmptyAssetAndTagListsWhenRepositoryReturnsNoRelatedRecords() {
		IllustrationRepository repository = mock(IllustrationRepository.class);
		IllustrationDetailService service = new IllustrationDetailService(repository);
		IllustrationDetailBase base = new IllustrationDetailBase(
				10L, null, null, null, null,
				LocalDateTime.of(2026, 9, 19, 10, 0),
				LocalDateTime.of(2026, 9, 19, 10, 0)
		);
		when(repository.findDetailBaseById(10L)).thenReturn(Optional.of(base));
		when(repository.findAssetSummariesByIllustrationId(10L)).thenReturn(List.of());
		when(repository.findTagSummariesByIllustrationId(10L)).thenReturn(List.of());

		var detail = service.getDetail(10L);

		assertEquals(List.of(), detail.assets());
		assertEquals(List.of(), detail.tags());
	}
}
