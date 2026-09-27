package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class XLikeSyncServiceTest {
	@Test
	void inboxKeepsNewestFirstAndOrdersEachItemsMedia() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikeSyncService service = new XLikeSyncService(mock(XApiClient.class),
				mock(XLikePersistenceService.class), items, media, mock(XLikeSyncStateRepository.class), 5, 3);
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
				mock(XLikeRepository.class), mock(XLikeMediaRepository.class), mock(XLikeSyncStateRepository.class), 5, 3);

		assertThrows(IllegalArgumentException.class, () -> service.syncRecent(4, 1));
		assertThrows(IllegalArgumentException.class, () -> service.syncRecent(101, 1));
		assertThrows(IllegalArgumentException.class, () -> service.syncRecent(5, 0));
		assertThrows(IllegalArgumentException.class, () -> service.syncRecent(5, 11));
		verifyNoInteractions(client, persistence);
	}

	@Test
	void onePageWithoutNextTokenReturnsItsCounts() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		XLikePage page = new XLikePage(List.of(), false, null);
		when(client.fetchRecentLikes("999", 5, null)).thenReturn(page);
		when(persistence.savePage(page)).thenReturn(new XLikeSyncSummary(1, 2, 1, 1, 1, 0, false, false, false));

		XLikeSyncSummary summary = service(client, persistence).syncRecent(null, null);

		assertEquals(1, summary.pagesFetched());
		assertEquals(2, summary.fetchedCount());
		assertEquals(1, summary.newCount());
		assertEquals(1, summary.existingCount());
		assertFalse(summary.hasMore());
		assertFalse(summary.stoppedByMaxPages());
		verify(client).fetchRecentLikes("999", 5, null);
		verify(client).resolveUserId();
		verifyNoMoreInteractions(client);
	}

	@Test
	void twoPagesAggregateCountsAndUseReturnedToken() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		XLikePage first = new XLikePage(List.of(), true, "second");
		XLikePage second = new XLikePage(List.of(), false, null);
		when(client.fetchRecentLikes("999", 5, null)).thenReturn(first);
		when(client.fetchRecentLikes("999", 5, "second")).thenReturn(second);
		when(persistence.savePage(first)).thenReturn(new XLikeSyncSummary(1, 3, 2, 1, 2, 1, true, false, false));
		when(persistence.savePage(second)).thenReturn(new XLikeSyncSummary(1, 2, 1, 1, 1, 0, false, false, false));

		XLikeSyncSummary summary = service(client, persistence).syncRecent(5, 3);

		assertEquals(2, summary.pagesFetched());
		assertEquals(5, summary.fetchedCount());
		assertEquals(3, summary.newCount());
		assertEquals(2, summary.existingCount());
		assertEquals(3, summary.pendingCount());
		assertEquals(1, summary.unsupportedCount());
		assertFalse(summary.hasMore());
		verify(client).fetchRecentLikes("999", 5, "second");
	}

	@Test
	void duplicatePostOnSecondPageUsesExistingPersistenceLogic() {
		XApiClient client = mock(XApiClient.class);
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		XLikeCandidate candidate = new XLikeCandidate("11", "a", "artist", "Artist", "text",
				Instant.parse("2026-09-25T09:00:00Z"), XLikeStatus.PENDING,
				List.of(new XLikeMedia("p1", 0, "photo", "https://img/1", 1, 1)));
		when(client.resolveUserId()).thenReturn("999");
		when(client.fetchRecentLikes("999", 5, null)).thenReturn(new XLikePage(List.of(candidate), true, "next"));
		when(client.fetchRecentLikes("999", 5, "next")).thenReturn(new XLikePage(List.of(candidate), false, null));
		when(items.insertIfAbsent(candidate)).thenReturn(7L, null);
		when(items.findStatusByPostId("11")).thenReturn(XLikeStatus.PENDING);
		XLikeSyncService service = new XLikeSyncService(client,
				new XLikePersistenceService(items, media), items, media, mock(XLikeSyncStateRepository.class), 5, 3);

		XLikeSyncSummary summary = service.syncRecent(5, 3);

		assertEquals(2, summary.pagesFetched());
		assertEquals(2, summary.fetchedCount());
		assertEquals(1, summary.newCount());
		assertEquals(1, summary.existingCount());
		assertEquals(2, summary.pendingCount());
		verify(media).insert(7L, candidate.media().get(0));
		verifyNoMoreInteractions(media);
	}

	@Test
	void maxPagesStopsEvenIfRemoteKeepsReturningTokens() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		XLikePage first = new XLikePage(List.of(), true, "second");
		XLikePage second = new XLikePage(List.of(), true, "third");
		when(client.fetchRecentLikes("999", 5, null)).thenReturn(first);
		when(client.fetchRecentLikes("999", 5, "second")).thenReturn(second);
		when(persistence.savePage(first)).thenReturn(new XLikeSyncSummary(1, 1, 1, 0, 1, 0, true, false, false));
		when(persistence.savePage(second)).thenReturn(new XLikeSyncSummary(1, 1, 1, 0, 1, 0, true, false, false));

		XLikeSyncSummary summary = service(client, persistence).syncRecent(5, 2);

		assertEquals(2, summary.pagesFetched());
		assertTrue(summary.hasMore());
		assertTrue(summary.stoppedByMaxPages());
		verify(client).fetchRecentLikes("999", 5, null);
		verify(client).fetchRecentLikes("999", 5, "second");
		verify(client).resolveUserId();
		verifyNoMoreInteractions(client);
	}

	@Test
	void missingOrRepeatedTokenStopsWithoutLooping() {
		for (String token : List.of("", "same")) {
			XApiClient client = mock(XApiClient.class);
			XLikePersistenceService persistence = mock(XLikePersistenceService.class);
			XLikePage first = new XLikePage(List.of(), true, token);
			when(client.fetchRecentLikes("999", 5, null)).thenReturn(first);
			when(persistence.savePage(first)).thenReturn(new XLikeSyncSummary(1, 0, 0, 0, 0, 0, true, false, false));
			if (token.isEmpty()) {
				XLikeSyncSummary summary = service(client, persistence).syncRecent(5, 3);
				assertEquals(1, summary.pagesFetched());
				assertTrue(summary.stoppedByInvalidToken());
				verify(client).fetchRecentLikes("999", 5, null);
			} else {
				XLikePage second = new XLikePage(List.of(), true, token);
				when(client.fetchRecentLikes("999", 5, token)).thenReturn(second);
				XLikeSyncSummary summary = service(client, persistence).syncRecent(5, 3);
				assertEquals(2, summary.pagesFetched());
				assertTrue(summary.stoppedByInvalidToken());
				verify(client).fetchRecentLikes("999", 5, null);
				verify(client).fetchRecentLikes("999", 5, token);
			}
			verify(client).resolveUserId();
			verifyNoMoreInteractions(client);
		}
	}

	@Test
	void nextManualSyncContinuesBeyondFifteenAndCompletionRestartsAtLatest() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		MemoryContinuation memory = new MemoryContinuation();
		when(client.resolveUserId()).thenReturn("999");
		XLikePage first = page(1, 5, "page-2");
		XLikePage second = page(6, 10, "page-3");
		XLikePage third = page(11, 15, "page-4");
		XLikePage fourth = page(16, 16, null);
		XLikePage latest = page(17, 17, null);
		when(client.fetchRecentLikes("999", 5, null)).thenReturn(first, latest);
		when(client.fetchRecentLikes("999", 5, "page-2")).thenReturn(second);
		when(client.fetchRecentLikes("999", 5, "page-3")).thenReturn(third);
		when(client.fetchRecentLikes("999", 5, "page-4")).thenReturn(fourth);
		when(persistence.savePage(any(XLikePage.class))).thenAnswer(call -> {
			XLikePage page = call.getArgument(0);
			int count = page.candidates().size();
			return new XLikeSyncSummary(1, count, count, 0, count, 0, page.hasMore(), false, false);
		});

		XLikeSyncSummary capped = service(client, persistence, memory.repo).syncRecent(5, 3);
		assertEquals(3, capped.pagesFetched());
		assertEquals(15, capped.newCount());
		assertTrue(capped.stoppedByMaxPages());
		assertEquals("page-4", memory.saved.get().nextToken());

		XLikeSyncSummary resumed = service(client, persistence, memory.repo).syncRecent(5, 3);
		assertEquals(1, resumed.pagesFetched());
		assertEquals(1, resumed.newCount());
		assertFalse(resumed.hasMore());
		assertNull(memory.saved.get());
		verify(client).fetchRecentLikes("999", 5, "page-4");

		XLikeSyncSummary newest = service(client, persistence, memory.repo).syncRecent(5, 3);
		assertEquals(1, newest.newCount());
		verify(client, times(2)).fetchRecentLikes("999", 5, null);
		verify(client, times(3)).resolveUserId();
	}

	@Test
	void repeatedTokenAcrossManualSyncsBlocksUntilExplicitReset() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		MemoryContinuation memory = new MemoryContinuation();
		when(client.resolveUserId()).thenReturn("999");
		XLikePage first = page(1, 1, "same");
		XLikePage repeated = page(2, 2, "same");
		when(client.fetchRecentLikes("999", 5, null)).thenReturn(first);
		when(client.fetchRecentLikes("999", 5, "same")).thenReturn(repeated);
		when(persistence.savePage(any(XLikePage.class))).thenReturn(new XLikeSyncSummary(1, 1, 1, 0, 1, 0, true, false, false));

		service(client, persistence, memory.repo).syncRecent(5, 1);
		XLikeSyncSummary invalid = service(client, persistence, memory.repo).syncRecent(5, 3);
		assertTrue(invalid.stoppedByInvalidToken());
		assertEquals("INVALID", memory.saved.get().status());
		assertThrows(XApiException.class, () -> service(client, persistence, memory.repo).syncRecent(5, 3));
		verify(client, times(2)).resolveUserId();
		service(client, persistence, memory.repo).resetInvalidContinuation();
		assertNull(memory.saved.get());
	}

	@Test
	void savedContinuationRequiresSamePageSizeAndAUsableToken() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		MemoryContinuation memory = new MemoryContinuation();
		memory.repo.advance("saved", 5);

		assertThrows(IllegalArgumentException.class,
				() -> service(client, persistence, memory.repo).syncRecent(6, 3));
		assertEquals("saved", memory.saved.get().nextToken());
		memory.saved.set(new XLikeSyncStateRepository.State(null, 5, "ACTIVE"));
		assertThrows(XApiException.class,
				() -> service(client, persistence, memory.repo).syncRecent(5, 3));
		assertEquals("INVALID", memory.saved.get().status());
		verifyNoInteractions(client, persistence);
	}

	@Test
	void rejectedSavedTokenMarksContinuationInvalidButRateLimitKeepsIt() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		MemoryContinuation memory = new MemoryContinuation();
		memory.repo.advance("saved", 5);
		when(client.resolveUserId()).thenReturn("999");
		when(client.fetchRecentLikes("999", 5, "saved"))
				.thenThrow(new XApiException("rate limited", 429))
				.thenThrow(new XApiException("invalid token", 400));

		assertEquals(429, assertThrows(XApiException.class,
				() -> service(client, persistence, memory.repo).syncRecent(5, 3)).upstreamStatus());
		assertEquals("ACTIVE", memory.saved.get().status());
		XApiException invalid = assertThrows(XApiException.class,
				() -> service(client, persistence, memory.repo).syncRecent(5, 3));
		assertEquals(400, invalid.upstreamStatus());
		assertEquals("INVALID", memory.saved.get().status());
		verifyNoInteractions(persistence);
	}

	@Test
	void failedPageSaveDoesNotAdvanceSavedToken() {
		XApiClient client = mock(XApiClient.class);
		XLikePersistenceService persistence = mock(XLikePersistenceService.class);
		MemoryContinuation memory = new MemoryContinuation();
		memory.repo.advance("saved", 5);
		when(client.resolveUserId()).thenReturn("999");
		XLikePage page = page(6, 10, "next");
		when(client.fetchRecentLikes("999", 5, "saved")).thenReturn(page);
		when(persistence.savePage(page)).thenThrow(new IllegalStateException("database failure"));

		assertThrows(IllegalStateException.class,
				() -> service(client, persistence, memory.repo).syncRecent(5, 3));
		assertEquals("saved", memory.saved.get().nextToken());
	}

	private XLikePage page(int first, int last, String nextToken) {
		List<XLikeCandidate> candidates = java.util.stream.IntStream.rangeClosed(first, last)
				.mapToObj(id -> new XLikeCandidate(String.valueOf(id), "a", "artist", "Artist", null,
						null, XLikeStatus.UNSUPPORTED, List.of())).toList();
		return new XLikePage(candidates, nextToken != null, nextToken);
	}

	private static class MemoryContinuation {
		final XLikeSyncStateRepository repo = mock(XLikeSyncStateRepository.class);
		final AtomicReference<XLikeSyncStateRepository.State> saved = new AtomicReference<>();
		final Set<String> seen = new HashSet<>();

		MemoryContinuation() {
			when(repo.load()).thenAnswer(call -> Optional.ofNullable(saved.get()));
			when(repo.hasSeen(anyString())).thenAnswer(call -> seen.contains(call.getArgument(0)));
			doAnswer(call -> {
				String token = call.getArgument(0);
				int size = call.getArgument(1);
				seen.add(token);
				saved.set(new XLikeSyncStateRepository.State(token, size, "ACTIVE"));
				return null;
			}).when(repo).advance(anyString(), anyInt());
			doAnswer(call -> {
				saved.set(new XLikeSyncStateRepository.State(null, call.getArgument(0), "INVALID"));
				return null;
			}).when(repo).markInvalid(anyInt());
			doAnswer(call -> {
				saved.set(null);
				seen.clear();
				return null;
			}).when(repo).clear();
		}
	}

	private XLikeSyncService service(XApiClient client, XLikePersistenceService persistence,
			XLikeSyncStateRepository state) {
		return new XLikeSyncService(client, persistence, mock(XLikeRepository.class),
				mock(XLikeMediaRepository.class), state, 5, 3);
	}

	private XLikeSyncService service(XApiClient client, XLikePersistenceService persistence) {
		when(client.resolveUserId()).thenReturn("999");
		return new XLikeSyncService(client, persistence, mock(XLikeRepository.class),
				mock(XLikeMediaRepository.class), mock(XLikeSyncStateRepository.class), 5, 3);
	}

	@Test
	void skipCountsOnlyPendingTransitionsAndDeduplicatesIds() {
		XLikeRepository items = mock(XLikeRepository.class);
		XLikeSyncService service = new XLikeSyncService(mock(XApiClient.class),
				mock(XLikePersistenceService.class), items, mock(XLikeMediaRepository.class), mock(XLikeSyncStateRepository.class), 5, 3);
		when(items.skipIfPending(1L)).thenReturn(1);
		when(items.skipIfPending(2L)).thenReturn(1);
		// An already skipped, unsupported or unknown id returns zero from the conditional SQL update.
		var result = service.skip(List.of(1L, 2L, 2L, 3L, 4L, 99L));

		assertEquals(6, result.requestedCount());
		assertEquals(2, result.skippedCount());
		verify(items).skipIfPending(1L);
		verify(items).skipIfPending(2L);
		verify(items).skipIfPending(3L);
		verify(items).skipIfPending(4L);
		verify(items).skipIfPending(99L);
	}
}
