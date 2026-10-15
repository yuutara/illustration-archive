package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.*;
import com.yuutara.illustrationarchive.repository.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.http.*;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The real API parser is exercised against a mocked HttpClient; no sockets or X requests. */
class XLikeCombinedSyncTest {
	@Test
	void springUsesConfiguredBudgetAndRejectsUnsafeOrImpossibleLimits() {
		try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
			org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
					"x.sync.recent-page-size=10", "x.sync.recent-max-pages=1", "x.sync.max-pages=3",
					"x.sync.max-scanned=30", "x.sync.max-new=20");
			context.register(XLikeSyncBudget.class);
			context.refresh();
			assertEquals(new XLikeSyncBudget(10, 1, 3, 30, 20), context.getBean(XLikeSyncBudget.class));
		}
		assertThrows(IllegalArgumentException.class, () -> new XLikeSyncBudget(25, 2, 1, 100, 50));
		assertThrows(IllegalArgumentException.class, () -> new XLikeSyncBudget(25, 2, 6, 10, 50));
		assertThrows(IllegalArgumentException.class, () -> new XLikeSyncBudget(25, 2, 6, 100, 10));
	}

	@Test
	void oversizedApiPageCannotExceedTheNewItemBudget() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 1, 5, 5));
		f.reply(page(ids(1, 6), null));
		var result = f.service.sync();
		assertEquals("API_FAILURE", result.recent().stopReason());
		assertEquals(6, result.recent().scannedCount());
		assertEquals(0, result.recent().newCount());
		assertTrue(f.stored.isEmpty());
	}
	@Test
	void fiveNewLikesThenSavedHistoryKeepsItsPageSizeAndNewestRunsAgain() throws Exception {
		var f = new Fixture();
		f.state("saved", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "recent-2"));
		f.reply(page(ids(1, 5), "recent-tail"));
		f.reply(page(ids(6, 10), null));
		var result = f.service.sync();
		assertEquals(5, result.recent().newCount());
		assertEquals(5, result.history().newCount());
		assertEquals(15, result.recent().scannedCount() + result.history().scannedCount());
		assertEquals(5, result.recent().existingCount());
		assertEquals("COMPLETE", result.historyStatus());
		assertTrue(f.likesRequests().get(2).uri().toString().contains("max_results=5"));
		assertTrue(f.likesRequests().get(2).uri().toString().contains("pagination_token=saved"));
		assertTrue(f.order.get("1") > f.order.get("5"));
		assertTrue(f.order.get("5") > f.order.get("6"));
		f.reply(page(ids(11, 11), "recent-2-new"));
		f.reply(page(ids(1, 5), "recent-tail-new"));
		f.reply(page(ids(6, 10), null));
		var again = f.service.sync();
		assertEquals(1, again.recent().newCount());
		assertEquals(0, again.history().pagesRequested());
		assertEquals("HISTORY_COMPLETE", again.history().stopReason());
		assertFalse(f.likesRequests().get(3).uri().toString().contains("pagination_token"));
	}

	@Test
	void recordsAtSixteenThroughThirtyAreNotLimitedToFifteenAndKeepApiOrder() throws Exception {
		var f = new Fixture();
		var first = new ArrayList<>(ids(1, 25));
		Collections.reverse(first); // Neither Post ID nor post_created_at defines this sequence.
		var second = new ArrayList<>(ids(26, 29));
		second.add("2109573113432399935");
		f.reply(page(first, "page-2"));
		f.reply(page(second, null));
		var report = f.service.sync();
		assertEquals(30, report.recent().newCount());
		assertEquals(2, report.recent().pagesRequested());
		assertTrue(f.stored.containsKey("2109573113432399935"));
		var expected = new ArrayList<>(first); expected.addAll(second);
		assertEquals(expected, f.order.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.map(Map.Entry::getKey).toList());
	}

	@Test
	void duplicatesIncludingImportedSkippedAndSamePhotoDifferentPostsRemainDistinct() throws Exception {
		var f = new Fixture();
		f.stored.put("1", XLikeStatus.IMPORTED); f.stored.put("2", XLikeStatus.SKIPPED);
		// All fixture posts refer to the same media URL; Sync does not perform content deduplication.
		f.reply(page(List.of("1", "2", "3", "4"), null));
		var report = f.service.sync();
		assertEquals(2, report.recent().newCount());
		assertEquals(2, report.recent().existingCount());
		assertEquals(2, report.recent().processedCount());
		assertEquals(XLikeStatus.IMPORTED, f.stored.get("1"));
		assertEquals(XLikeStatus.SKIPPED, f.stored.get("2"));
		assertEquals(XLikeStatus.PENDING, f.stored.get("3"));
		assertEquals(XLikeStatus.PENDING, f.stored.get("4"));
		f.reply(page(List.of("1", "2", "3", "4"), null));
		assertEquals(0, f.service.sync().recent().newCount());
		verify(f.media, times(2)).insert(anyLong(), any());
	}

	@Test
	void aPostRepeatedAcrossRecentPagesKeepsItsFirstApiPosition() throws Exception {
		var f = new Fixture();
		f.reply(page(List.of("9", "1", "8"), "second"));
		f.reply(page(List.of("8", "7", "9"), null));
		var report = f.service.sync();
		assertEquals(4, report.recent().newCount());
		assertEquals(2, report.recent().existingCount());
		assertEquals(List.of("9", "1", "8", "7"), f.order.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList());
	}

	@Test
	void historyPageOverlapKeepsTheFirstPositionAndStillCountsDuplicates() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 4, 20, 50));
		f.state("old", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "latest-tail"));
		f.reply(page(List.of("9", "8"), "history-next"));
		f.reply(page(List.of("8", "7", "9"), null));
		var report = f.service.sync();
		assertEquals(3, report.history().newCount());
		assertEquals(2, report.history().existingCount());
		assertTrue(f.order.get("9") > f.order.get("8") && f.order.get("8") > f.order.get("7"));
	}

	@Test
	void incorrectResultCountDoesNotClaimNaturalEnd() throws Exception {
		var f = new Fixture();
		f.reply(page(ids(1, 2), null).replace("\"meta\":{", "\"meta\":{\"result_count\":5"));
		var report = f.service.sync();
		assertEquals("API_PARTIAL", report.recent().stopReason());
		assertEquals("UNKNOWN", report.historyStatus());
		assertEquals(2, report.recent().newCount());
		assertTrue(report.partial());
	}

	@Test
	void optionalNullErrorsAndCountAreNotPartialFailures() throws Exception {
		var f = new Fixture();
		f.reply(page(ids(1, 2), null).replace("\"meta\":{}", "\"errors\":null,\"meta\":{\"result_count\":null}"));
		var result = f.service.sync();
		assertFalse(result.partial());
		assertEquals("COMPLETE", result.historyStatus());
		assertEquals(2, result.recent().newCount());
	}

	@Test
	void failedCredentialRefreshDoesNotCountASecondLikesHttpAttempt() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 2, 10, 50));
		f.reply(401, "{}");
		when(f.tokens.refreshAfterRejection(anyString())).thenThrow(new XApiException("fixture refresh failure", null));
		var result = f.service.sync();
		assertEquals(1, result.recent().pagesRequested());
		assertEquals(1, f.likesRequests().size());
		assertTrue(result.partial());
	}

	@Test
	void allExistingPagesDoNotStopHistoryAndBudgetsAreSharedAcrossStages() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 3, 15, 10));
		f.state("old", 5, "ACTIVE");
		ids(1, 5).forEach(id -> f.stored.put(id, XLikeStatus.PENDING));
		f.reply(page(ids(1, 5), "latest-tail"));
		f.reply(page(ids(1, 5), "history-next"));
		f.reply(page(ids(6, 10), "history-more"));
		var report = f.service.sync();
		assertEquals(5, report.history().newCount());
		assertEquals(5, report.history().existingCount());
		assertEquals(3, f.likesRequests().size());
		assertEquals("PAGE_BUDGET", report.history().stopReason());
		assertEquals("history-more", f.saved.nextToken());
	}

	@Test
	void scanBudgetAndNewBudgetStopBeforeSendingAnotherWholePage() throws Exception {
		for (var limits : List.of(new XLikeSyncBudget(5, 1, 6, 5, 50), new XLikeSyncBudget(5, 1, 6, 100, 5))) {
			var f = new Fixture(limits);
			f.reply(page(ids(1, 5), "tail"));
			var result = f.service.sync();
			assertEquals(1, f.likesRequests().size());
			assertEquals(limits.maxScanned() == 5 ? "SCAN_BUDGET" : "NEW_BUDGET", result.recent().stopReason());
			assertEquals("tail", f.recentState.nextToken());
			assertNull(f.saved); // No handoff from a limited Recent window to a possibly distant History cursor.
			assertFalse(result.partial());
		}
	}

	@Test
	void failedHistoryPageKeepsRecentDataAndCursorThenManualRetryResumes() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 3, 15, 50));
		f.state("old", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "tail")); f.reply(429, "{}");
		var partial = f.service.sync();
		assertTrue(partial.partial());
		assertEquals(5, partial.recent().newCount());
		assertEquals(1, partial.history().pagesRequested());
		assertEquals(0, partial.history().pagesSaved());
		assertFalse(partial.history().scanCountKnown());
		assertEquals("old", f.saved.nextToken());
		f.reply(page(ids(1, 5), "tail-new")); f.reply(page(List.of(), "resume-next"));
		f.reply(page(ids(6, 10), null));
		var retry = f.service.sync();
		assertEquals(0, retry.recent().newCount());
		assertEquals(5, retry.history().newCount());
		assertEquals("COMPLETE", retry.historyStatus());
	}

	@Test
	void secondRecentPageFailureRetainsFirstPageAndResumesTheSavedFrontierAfterAHeadProbe() throws Exception {
		var f = new Fixture();
		f.state("saved", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "second")); f.reply(500, "{}");
		var partial = f.service.sync();
		assertTrue(partial.partial());
		assertEquals(5, partial.recent().newCount());
		assertEquals(2, partial.recent().pagesRequested());
		assertEquals(1, partial.recent().pagesSaved());
		assertEquals("SKIPPED_AFTER_FAILURE", partial.history().stopReason());
		assertEquals("saved", f.saved.nextToken());
		f.reply(page(ids(1, 5), "second")); f.reply(page(ids(6, 10), null));
		f.reply(page(List.of(), null)); // Independent History still resumes its own saved token.
		var retry = f.service.sync();
		assertEquals(5, retry.recent().newCount());
		assertEquals(5, retry.recent().existingCount());
		assertEquals(10, f.stored.size());
		assertTrue(f.likesRequests().get(3).uri().toString().contains("pagination_token=second"));
	}

	@Test
	void partialApiPageSavesValidPostsButDoesNotAdvanceHistoryOrClaimCompletion() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 3, 15, 50));
		f.state("old", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "tail"));
		f.reply(page(ids(6, 7), "unsafe-next").replace("\"meta\":", "\"errors\":[{\"title\":\"omitted\"}],\"meta\":"));
		var report = f.service.sync();
		assertTrue(report.partial());
		assertEquals("API_PARTIAL", report.history().stopReason());
		assertEquals(2, report.history().newCount());
		assertEquals("old", f.saved.nextToken());
		assertEquals("MORE", report.historyStatus());
		f.reply(page(ids(1, 5), "tail-new")); f.reply(page(List.of(), "resume-next"));
		f.reply(page(ids(6, 8), null));
		var retry = f.service.sync();
		assertEquals(1, retry.history().newCount());
		assertEquals(2, retry.history().existingCount());
		assertTrue(f.order.get("6") > f.order.get("7") && f.order.get("7") > f.order.get("8"));
	}

	@Test
	void expiredHistoryResetsToAnExplicitConservativeTraversalWithoutDroppingPosts() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 3, 15, 50));
		f.state("expired", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "tail")); f.reply(400, "{}");
		assertEquals("INVALID", f.service.sync().historyStatus());
		f.reply(page(ids(1, 5), "tail-new"));
		f.reply(page(List.of(), "resume-next"));
		var blocked = f.service.sync();
		assertEquals(0, blocked.history().pagesRequested());
		assertEquals(4, f.likesRequests().size());
		var beforeReset = Set.copyOf(f.stored.keySet());
		f.service.resetInvalidContinuation();
		assertEquals(beforeReset, f.stored.keySet());
		assertNull(f.recentState.anchorIds());
		f.reply(page(ids(1, 5), "fresh-tail"));
		assertEquals("WAIT_RECENT_BOOTSTRAP", f.service.sync().history().stopReason());
		f.reply(page(ids(1, 5), "probe-only")); f.reply(page(ids(6, 10), null));
		assertEquals(5, f.service.sync().recent().newCount());
		assertTrue(f.likesRequests().get(6).uri().toString().contains("pagination_token=fresh-tail"));
		f.reply(page(ids(1, 5), "probe-only-2"));
		assertEquals("COMPLETE", f.service.sync().historyStatus());
	}

	@Test
	void repeatedHistoryTokenStopsAndMissingAuthorCountsReceivedDataWithoutSavingPage() throws Exception {
		var f = new Fixture(new XLikeSyncBudget(5, 1, 3, 15, 50));
		f.state("old", 5, "ACTIVE");
		f.reply(page(ids(1, 5), "tail")); f.reply(page(ids(6, 10), "old"));
		assertEquals("INVALID", f.service.sync().historyStatus());
		var missing = new Fixture();
		missing.reply("{\"data\":[{\"id\":\"1\",\"author_id\":\"7\"}]}");
		var report = missing.service.sync();
		assertTrue(report.partial());
		assertEquals(1, report.recent().scannedCount());
		assertTrue(report.recent().scanCountKnown());
		assertEquals(0, report.recent().pagesSaved());
		assertTrue(missing.stored.isEmpty());
	}

	@Test
	void credentialRetryConsumesBudgetAndCannotSendAnUnbudgetedSecondRequest() throws Exception {
		for (int pages : List.of(1, 2)) {
			var f = new Fixture(new XLikeSyncBudget(5, 1, pages, 10, 50));
			f.reply(401, "{}"); f.reply(page(ids(1, 5), null));
			var report = f.service.sync();
			assertEquals(pages, f.likesRequests().size());
			assertEquals(pages, report.recent().pagesRequested());
			assertEquals(pages == 1 ? "PAGE_BUDGET" : "CAUGHT_UP", report.recent().stopReason());
			assertEquals(pages == 1, report.partial());
		}
	}

	@Test
	void saveOrCursorFailureReportsCommittedDataAndDoesNotLoseRetryPosition() throws Exception {
		for (boolean cursorFailure : List.of(false, true)) {
			var f = new Fixture(new XLikeSyncBudget(5, 1, 3, 15, 50));
			f.state("old", 5, "ACTIVE");
			f.reply(page(ids(1, 5), "tail")); f.reply(page(ids(6, 10), "next"));
			if (cursorFailure) doThrow(new IllegalStateException("fixture")).when(f.state).advance("next", 5);
			else doThrow(new IllegalStateException("fixture")).when(f.persistence)
					.saveOrderedPage(any(), anyLong(), eq(true), anySet());
			var report = f.service.sync();
			assertTrue(report.partial());
			assertEquals(cursorFailure ? "STATE_FAILURE" : "SAVE_FAILURE", report.history().stopReason());
			assertEquals(cursorFailure ? 5 : 0, report.history().newCount());
			assertEquals("old", f.saved.nextToken());
		}
	}

	private static List<String> ids(int first, int last) {
		return IntStream.rangeClosed(first, last).mapToObj(Integer::toString).toList();
	}

	static String page(List<String> ids, String next) {
		String posts = String.join(",", ids.stream().map(id -> "{\"id\":\"" + id
				+ "\",\"author_id\":\"7\",\"created_at\":\"2000-01-01T00:00:00Z\",\"attachments\":{\"media_keys\":[\"photo\"]}}").toList());
		return "{\"data\":[" + posts + "],\"includes\":{\"users\":[{\"id\":\"7\",\"username\":\"artist\",\"name\":\"Artist\"}],"
				+ "\"media\":[{\"media_key\":\"photo\",\"type\":\"photo\",\"url\":\"https://example.test/same.jpg\"}]},\"meta\":{"
				+ (next == null ? "" : "\"next_token\":\"" + next + "\"") + "}}";
	}

	static class Fixture {
		final List<HttpRequest> requests = new ArrayList<>();
		final Queue<HttpResponse<String>> replies = new ArrayDeque<>();
		final Map<String, XLikeStatus> stored = new HashMap<>();
		final Map<String, Long> order = new HashMap<>();
		final Set<String> seen = new HashSet<>();
		final XLikeRepository items = mock(XLikeRepository.class);
		final XLikeMediaRepository media = mock(XLikeMediaRepository.class);
		final XLikeSyncStateRepository state = mock(XLikeSyncStateRepository.class);
		final XTokenManager tokens = mock(XTokenManager.class);
		final XLikePersistenceService persistence = spy(new XLikePersistenceService(items, media, state));
		XLikeSyncService service;
		final XApiClient api;
		final XLikeSyncBudget limits;
		java.util.function.Function<HttpRequest,HttpResponse<String>> responder;
		XLikeSyncStateRepository.RecentState recentState;
		final Set<String> recentSeen = new HashSet<>();
		XLikeSyncStateRepository.State saved;

		Fixture() throws Exception { this(new XLikeSyncBudget(25, 2, 6, 100, 50)); }
		@SuppressWarnings("unchecked")
		Fixture(XLikeSyncBudget budget) throws Exception {
			limits = budget;
			var http = mock(HttpClient.class);
			when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
				HttpRequest request = call.getArgument(0); requests.add(request);
				if (request.uri().getPath().endsWith("/me")) return response(200, "{\"data\":{\"id\":\"9\"}}");
				if (responder != null) return responder.apply(request);
				assertFalse(replies.isEmpty(), "Unexpected extra Likes request");
				return replies.remove();
			});
			when(tokens.currentAccessToken()).thenReturn("fixture-credential");
			when(tokens.refreshAfterRejection(anyString())).thenReturn("fixture-refreshed");
			when(items.insertIfAbsent(any(), anyLong())).thenAnswer(call -> {
				XLikeCandidate candidate = call.getArgument(0);
				if (stored.containsKey(candidate.xPostId())) return null;
				stored.put(candidate.xPostId(), candidate.status()); order.put(candidate.xPostId(), call.getArgument(1));
				return (long) stored.size();
			});
			when(items.findStatusByPostId(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
			when(items.highestLikesOrder()).thenAnswer(call -> Math.max(0, order.values().stream().mapToLong(Long::longValue).max().orElse(0)));
			when(items.lowestLikesOrder()).thenAnswer(call -> Math.min(0, order.values().stream().mapToLong(Long::longValue).min().orElse(0)));
			doAnswer(call -> {
				String id = call.getArgument(0); boolean history = call.getArgument(2);
				long rank = call.getArgument(1);
				if (history ? order.getOrDefault(id, 0L) <= 0 : order.getOrDefault(id, 0L) < rank) order.put(id, rank);
				return null;
			}).when(items).updateLikesOrder(anyString(), anyLong(), anyBoolean());
			doAnswer(call -> {
				String id = call.getArgument(0); long rank = call.getArgument(1), floor = call.getArgument(2);
				long first = call.getArgument(3), last = call.getArgument(4), old = order.getOrDefault(id,0L);
				if (old <= floor || (old >= last && old <= first)) order.put(id,rank);
				return null;
			}).when(items).updateRecentOrder(anyString(),anyLong(),anyLong(),anyLong(),anyLong());
			when(state.loadRecent()).thenAnswer(call -> Optional.ofNullable(recentState));
			when(state.recentHasSeen(anyString())).thenAnswer(call -> recentSeen.contains(call.getArgument(0)));
			doAnswer(call -> { saveRecent(call.getArgument(0)); return null; }).when(state).saveRecent(any());
			doAnswer(call -> { recentSeen.clear(); saveRecent(call.getArgument(0)); return null; }).when(state).restartRecent(any());
			when(state.load()).thenAnswer(call -> Optional.ofNullable(saved));
			when(state.hasSeen(anyString())).thenAnswer(call -> seen.contains(call.getArgument(0)));
			doAnswer(call -> { state(call.getArgument(0), call.getArgument(1), "ACTIVE"); return null; })
					.when(state).advance(anyString(), anyInt());
			doAnswer(call -> { saved = new XLikeSyncStateRepository.State(null, call.getArgument(0), "INVALID"); return null; })
					.when(state).markInvalid(anyInt());
			doAnswer(call -> { saved = new XLikeSyncStateRepository.State(null, call.getArgument(0), "COMPLETE"); seen.clear(); return null; })
					.when(state).markComplete(anyInt());
			doAnswer(call -> { saved = null; seen.clear(); return null; }).when(state).clear();
			api = new XApiClient(http, new ObjectMapper(), true, "https://example.test", tokens);
			service = newService();
		}
		XLikeSyncService newService() { return new XLikeSyncService(api,persistence,items,media,state,5,3,limits); }
		void saveRecent(XLikeSyncStateRepository.RecentState value) {
			recentState = value;
			if (value.nextToken()!=null && "ACTIVE".equals(value.status())) recentSeen.add(value.nextToken());
		}
		void state(String token, int size, String status) { saved = new XLikeSyncStateRepository.State(token, size, status); if (token != null) seen.add(token); }
		void reply(String body) { reply(200, body); }
		void reply(int status, String body) { replies.add(response(status, body)); }
		List<HttpRequest> likesRequests() { return requests.stream().filter(r -> r.uri().getPath().endsWith("liked_tweets")).toList(); }
		@SuppressWarnings("unchecked")
		static HttpResponse<String> response(int status, String body) {
			HttpResponse<String> response = mock(HttpResponse.class);
			when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
			return response;
		}
	}
}
