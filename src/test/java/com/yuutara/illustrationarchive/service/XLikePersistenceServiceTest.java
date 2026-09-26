package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class XLikePersistenceServiceTest {
	@Test
	void repeatedPostDoesNotInsertSecondItemOrMediaAndCountsExistingStatus() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikePersistenceService service = new XLikePersistenceService(items, media);
		XLikeMedia photo = new XLikeMedia("p1", 0, "photo", "https://img/1", 100, 200);
		XLikeCandidate candidate = candidate("11", XLikeStatus.PENDING, List.of(photo));
		when(items.insertIfAbsent(candidate)).thenReturn(7L, null);
		when(items.findStatusByPostId("11")).thenReturn(XLikeStatus.PENDING);

		var first = service.savePage(new XLikePage(List.of(candidate), true));
		var second = service.savePage(new XLikePage(List.of(candidate), false));

		assertEquals(1, first.newCount());
		assertEquals(0, first.existingCount());
		assertEquals(1, first.pendingCount());
		assertEquals(true, first.hasMore());
		assertEquals(0, second.newCount());
		assertEquals(1, second.existingCount());
		assertEquals(1, second.pendingCount());
		verify(media).insert(7L, photo);
		verifyNoMoreInteractions(media);
	}

	@Test
	void multiplePhotosUseOneItemAndStoreEverySortPosition() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikeMedia first = new XLikeMedia("p3", 0, "photo", "https://img/3", null, null);
		XLikeMedia second = new XLikeMedia("p2", 1, "photo", "https://img/2", null, null);
		XLikeCandidate candidate = candidate("12", XLikeStatus.PENDING, List.of(first, second));
		when(items.insertIfAbsent(candidate)).thenReturn(8L);

		var summary = new XLikePersistenceService(items, media).savePage(new XLikePage(List.of(candidate), false));

		assertEquals(1, summary.fetchedCount());
		assertEquals(1, summary.newCount());
		verify(media).insert(8L, first);
		verify(media).insert(8L, second);
	}

	@Test
	void unsupportedItemIsSavedWithoutPhotoMedia() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikeCandidate candidate = candidate("13", XLikeStatus.UNSUPPORTED, List.of());
		when(items.insertIfAbsent(candidate)).thenReturn(9L);

		var summary = new XLikePersistenceService(items, media).savePage(new XLikePage(List.of(candidate), false));

		assertEquals(1, summary.unsupportedCount());
		verifyNoMoreInteractions(media);
	}

	@Test
	void syncingSkippedPostKeepsSkippedStatusAndDoesNotReinsertMedia() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikeCandidate candidate = candidate("11", XLikeStatus.PENDING,
				List.of(new XLikeMedia("p1", 0, "photo", "https://img/1", 100, 200)));
		when(items.insertIfAbsent(candidate)).thenReturn(null);
		when(items.findStatusByPostId("11")).thenReturn(XLikeStatus.SKIPPED);

		var summary = new XLikePersistenceService(items, media)
				.savePage(new XLikePage(List.of(candidate), false));

		assertEquals(0, summary.newCount());
		assertEquals(1, summary.existingCount());
		assertEquals(0, summary.pendingCount());
		assertEquals(0, summary.unsupportedCount());
		verifyNoMoreInteractions(media);
	}

	private XLikeCandidate candidate(String id, XLikeStatus status, List<XLikeMedia> media) {
		return new XLikeCandidate(id, "a", "author", "Author", "text",
				Instant.parse("2026-09-25T09:00:00Z"), status, media);
	}
}
