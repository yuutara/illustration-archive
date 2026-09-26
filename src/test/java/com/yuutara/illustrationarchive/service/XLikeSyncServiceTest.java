package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class XLikeSyncServiceTest {
	@Test
	void inboxKeepsNewestFirstAndOrdersEachItemsMedia() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikeSyncService service = new XLikeSyncService(mock(XApiClient.class),
				mock(XLikePersistenceService.class), items, media, 5);
		Instant recent = Instant.parse("2026-09-25T09:00:00Z");
		Instant older = Instant.parse("2026-09-24T09:00:00Z");
		when(items.findPending()).thenReturn(List.of(
				new XLikeInboxItem(2, "new", "New", "new", "recent", recent, List.of()),
				new XLikeInboxItem(1, "old", "Old", "old", "older", older, List.of())));
		when(media.findForPendingItems()).thenReturn(List.of(
				new XLikeMediaRepository.PendingMedia(1, new XLikeMedia("old-photo", 0, "photo", "https://img/old", 1, 2)),
				new XLikeMediaRepository.PendingMedia(2, new XLikeMedia("first", 0, "photo", "https://img/1", 3, 4)),
				new XLikeMediaRepository.PendingMedia(2, new XLikeMedia("second", 1, "photo", "https://img/2", 5, 6))));

		List<XLikeInboxItem> inbox = service.inbox();

		assertEquals(List.of("new", "old"), inbox.stream().map(XLikeInboxItem::xPostId).toList());
		assertEquals(List.of("first", "second"), inbox.get(0).media().stream().map(XLikeMedia::mediaKey).toList());
	}

	@Test
	void invalidSizeStopsBeforeAnyNetworkOrDatabaseAccess() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		XLikeSyncService service = new XLikeSyncService(client, persistence,
				mock(XLikeRepository.class), mock(XLikeMediaRepository.class), 5);

		assertThrows(IllegalArgumentException.class, () -> service.syncRecent(4));
		assertThrows(IllegalArgumentException.class, () -> service.syncRecent(101));
		verifyNoInteractions(client, persistence);
	}
}
