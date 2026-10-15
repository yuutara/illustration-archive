package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.*;
import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import org.junit.jupiter.api.Test;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;

import static com.yuutara.illustrationarchive.service.XLikeCombinedSyncTest.page;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Models both live offsets and frozen token snapshots; production never decodes a token. */
class XLikeCatchupTest {
	@Test
	void repeatedFiveNewLikesBridgeAWholeVerifiedWindow() throws Exception {
		var run = new Run(false, true);
		for (int round = 0; round < 4; round++) {
			run.add("round-" + round, 5);
			run.finish(30);
			run.assertExactIds();
		}
	}

	@Test
	void eightyAndOneHundredFiftyAreEventuallyCompleteForLiveAndSnapshotTokens() throws Exception {
		for (boolean snapshot : List.of(false, true)) for (int count : List.of(80, 150)) {
			var run = new Run(snapshot, true);
			run.add("new", count);
			var first = run.sync();
			assertEquals(50, first.recent().newCount());
			assertEquals("ACTIVE", first.recentStatus());
			assertNotNull(run.f.recentState.nextToken());
			run.sync();
			if (count == 80) assertTrue(run.f.stored.keySet().containsAll(ids("new", 80)));
			run.finish(40);
			run.assertExactIds();
			assertEquals("COMPLETE", run.f.saved.status());
		}
	}

	@Test
	void newLikesDuringCatchupAreReconciledRatherThanStrandedBehindHeadProbes() throws Exception {
		for (boolean snapshot : List.of(false, true)) {
			var run = new Run(snapshot, true);
			run.add("burst", 80); run.sync();
			run.add("during", 80); run.sync();
			run.add("last", 10);
			run.finish(60);
			run.assertExactIds(); // all 170 new IDs, not merely a matching count
			run.assertApiOrder();
		}
	}

	@Test
	void oldHistoryCursorAndRecentFrontierBothMakeProgress() throws Exception {
		var run = new Run(true, false);
		run.add("new", 150);
		String oldCursor = run.f.saved.nextToken();
		run.sync();
		assertEquals(oldCursor, run.f.saved.nextToken()); // new-item budget consumed by Recent
		run.finish(100);
		run.assertExactIds(); // includes the 300 initially missing OLD records as well
		assertEquals("COMPLETE", run.f.saved.status());
		assertTrue(run.model.requestedTokens.contains(oldCursor));
	}

	@Test
	void processRestartRestoresCursorAnchorMatcherAndOrder() throws Exception {
		var run = new Run(false, true);
		run.add("restart", 150); run.sync();
		String cursor = run.f.recentState.nextToken();
		run.f.service = run.f.newService();
		run.sync();
		assertTrue(run.model.requestedTokens.contains(cursor));
		for (int i = 0; i < 20 && "ACTIVE".equals(run.f.recentState.status()) && run.f.recentState.matched() == 0; i++) run.sync();
		assertNotEquals("ACTIVE", run.f.recentState.status());
		run.f.service = run.f.newService();
		run.finish(40);
		run.assertExactIds();
		run.assertApiOrder();
	}

	@Test
	void failureRetriesTheSameFrontierAndExpiredTokenNeedsAnExplicitSafeReset() throws Exception {
		for (int status : List.of(500, 429, 400)) {
			var run = new Run(true, true);
			run.add("recover", 80); run.sync();
			String cursor = run.f.recentState.nextToken();
			run.model.failToken = cursor; run.model.failStatus = status;
			var failed = run.sync();
			assertTrue(failed.partial());
			assertEquals(cursor, run.f.recentState.nextToken());
			assertEquals(status == 400 ? "INVALID" : "ACTIVE", failed.recentStatus());
			if (status == 400) {
				var before = Set.copyOf(run.f.stored.keySet());
				run.f.service.resetInvalidRecent();
				assertEquals(before, run.f.stored.keySet());
				assertEquals("COMPLETE", run.f.saved.status());
			}
			run.finish(50); run.assertExactIds();
		}
	}

	@Test
	void oneExistingOrImportedPostCannotTerminateCatchup() throws Exception {
		var run = new Run(false, true);
		run.add("new", 80);
		String imported = run.oldIds.get(200);
		run.f.stored.put(imported, XLikeStatus.IMPORTED);
		run.model.posts.remove(imported); run.model.posts.add(4, imported);
		run.finish(50);
		run.assertExactIds();
		assertEquals(XLikeStatus.IMPORTED, run.f.stored.get(imported));
	}

	@Test
	void missingOldAnchorFallsBackToTheNaturalEndAndDoesNotDeleteLocalData() throws Exception {
		var run = new Run(false, true);
		run.add("new", 80); run.sync();
		String missing = run.oldIds.get(5);
		run.model.posts.remove(missing);
		run.finish(60); run.assertExactIds();
		assertTrue(run.f.stored.containsKey(missing));
		assertEquals("CAUGHT_UP", run.f.recentState.status());
	}

	@Test
	void bootstrapWithoutAnyTrustedAnchorSweepsToEndDespiteAlreadySavedPosts() throws Exception {
		var run = new Run(false, true);
		run.f.recentState = null;
		run.add("bootstrap", 80);
		run.finish(60); run.assertExactIds(); run.assertApiOrder();
		assertTrue(run.f.recentState.coverageToEnd());
	}

	@Test
	void invalidHistoryCanBeResetAfterAReadAndTheRebuiltRangeConverges() throws Exception {
		var run = new Run(true, false);
		run.add("before-reset", 80); run.sync();
		run.f.state(null, 5, "INVALID");
		run.f.service = run.f.newService();
		int requests = run.f.requests.size();
		assertEquals("INVALID", run.f.service.progress().historyStatus());
		run.f.service.resetInvalidContinuation();
		assertEquals(requests, run.f.requests.size()); // GET/reset never consults X.
		assertNull(run.f.recentState.anchorIds());
		run.finish(100); run.assertExactIds();
	}

	@Test
	void onePageBudgetsResumeInsteadOfSpendingEveryClickOnTheHead() throws Exception {
		for (var budget : List.of(new XLikeSyncBudget(5, 1, 1, 5, 5), new XLikeSyncBudget(5, 1, 6, 30, 5))) {
			var run = new Run(true, true, budget);
			run.add("tiny", 80);
			run.finish(120); run.assertExactIds();
		}
	}

	@Test
	void prefixMatchAcrossPagesIsPersistedAndARepeatedTokenDoesNotPretendToComplete() throws Exception {
		var run = new Run(false, true);
		run.add("prefix", 80); run.sync(); run.sync();
		assertEquals(20, run.f.recentState.matched());
		run.f.service = run.f.newService();
		run.finish(40); run.assertExactIds();

		var repeated = new Run(false, true);
		repeated.add("loop", 150); repeated.sync();
		repeated.model.repeatToken = repeated.f.recentState.nextToken();
		var stopped = repeated.sync();
		assertTrue(stopped.partial()); assertEquals("INVALID", stopped.recentStatus());
		assertTrue(stopped.recent().hasMore());
	}

	@Test
	void headChangeDuringVerificationStartsAnotherBridgeSweep() throws Exception {
		var run = new Run(true, true);
		run.add("first", 80);
		for (int i = 0; i < 20 && !"VERIFY".equals(run.f.recentState.status()); i++) run.sync();
		assertEquals("VERIFY", run.f.recentState.status());
		run.add("verify-change", 80);
		var report = run.sync();
		assertNotEquals("CAUGHT_UP", report.recentStatus());
		run.finish(60); run.assertExactIds();
	}

	private static List<String> ids(String group, int count) {
		return IntStream.range(0, count).mapToObj(i -> group + "-" + i).toList();
	}

	private static class Run {
		final XLikeCombinedSyncTest.Fixture f;
		final Model model;
		final List<String> oldIds;
		final Set<String> expected = new HashSet<>();
		Run(boolean snapshot, boolean historyComplete) throws Exception {
			this(snapshot, historyComplete, new XLikeSyncBudget(25, 2, 6, 100, 50));
		}
		Run(boolean snapshot, boolean historyComplete, XLikeSyncBudget limits) throws Exception {
			f = new XLikeCombinedSyncTest.Fixture(limits);
			oldIds = ids("old", historyComplete ? 300 : 600);
			model = new Model(snapshot, oldIds); f.responder = model::respond;
			for (String id : oldIds.subList(0, 300)) { f.stored.put(id, XLikeStatus.PENDING); f.order.put(id, 0L); }
			expected.addAll(oldIds);
			String historyToken = historyComplete ? null : model.token(oldIds, 300);
			f.state(historyToken, 5, historyComplete ? "COMPLETE" : "ACTIVE");
			f.recentState = new XLikeSyncStateRepository.RecentState(null, limits.recentPageSize(), "CAUGHT_UP",
					List.copyOf(oldIds.subList(0, limits.recentPageSize())), List.copyOf(oldIds.subList(0, limits.recentPageSize())),
					0, 10000, 0, historyComplete);
		}
		void add(String group, int count) {
			var fresh = ids(group, count); model.posts.addAll(0, fresh); expected.addAll(fresh);
		}
		XLikeSyncReport sync() {
			int before = f.likesRequests().size();
			var report = f.service.sync();
			assertTrue(f.likesRequests().size() - before <= f.limits.maxPages());
			assertEquals(f.likesRequests().size() - before, report.recent().pagesRequested() + report.history().pagesRequested());
			assertTrue(report.recent().reservedScanCount() + report.history().reservedScanCount() <= f.limits.maxScanned());
			assertTrue(report.recent().newCount() + report.history().newCount() <= f.limits.maxNew());
			return report;
		}
		void finish(int limit) {
			for (int i = 0; i < limit; i++) {
				var report = sync();
				assertFalse(report.partial(), report.recent().stopReason() + "/" + report.history().stopReason());
				if ("CAUGHT_UP".equals(report.recentStatus()) && "COMPLETE".equals(report.historyStatus())) return;
			}
			fail("Catchup did not converge within the bounded test clicks");
		}
		void assertExactIds() { assertEquals(expected, f.stored.keySet()); }
		void assertApiOrder() {
			// Unswept V6 rows deliberately retain discovery/ID fallback; only API-observed ranks claim API order.
			var ranked = f.order.entrySet().stream().filter(e -> e.getValue() > 0)
					.sorted(Map.Entry.<String,Long>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
			assertEquals(model.posts.stream().filter(ranked::contains).toList(), ranked);
		}
	}

	private static class Model {
		final boolean snapshots;
		final List<String> posts;
		final Map<String, Slice> tokens = new HashMap<>();
		final List<String> requestedTokens = new ArrayList<>();
		String failToken, repeatToken;
		int failStatus, serial;
		Model(boolean snapshots, List<String> posts) { this.snapshots = snapshots; this.posts = new ArrayList<>(posts); }
		record Slice(List<String> snapshot, int offset) { }
		String token(List<String> data, int offset) {
			String value = "opaque-" + ++serial;
			tokens.put(value, new Slice(snapshots ? List.copyOf(data) : null, offset)); return value;
		}
		HttpResponse<String> respond(HttpRequest request) {
			Map<String,String> query = new HashMap<>();
			for (String part : request.uri().getRawQuery().split("&")) {
				var pair = part.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
			}
			String cursor = query.get("pagination_token"); requestedTokens.add(cursor);
			if (cursor != null && cursor.equals(failToken)) {
				failToken = null; return XLikeCombinedSyncTest.Fixture.response(failStatus, "{}");
			}
			Slice slice = cursor == null ? new Slice(null, 0) : tokens.get(cursor);
			assertNotNull(slice, "Unknown fixture cursor");
			List<String> data = snapshots && slice.snapshot() != null ? slice.snapshot() : posts;
			int start = Math.min(slice.offset(), data.size());
			int end = Math.min(start + Integer.parseInt(query.get("max_results")), data.size());
			String next = end < data.size() ? token(data, end) : null;
			if (cursor != null && cursor.equals(repeatToken)) next = cursor;
			String json = page(data.subList(start, end), next);
			// Post creation times are deliberately unrelated to Likes order.
			json = json.replace("2000-01-01T00:00:00Z", start % 2 == 0 ? "2040-01-01T00:00:00Z" : "1990-01-01T00:00:00Z");
			return XLikeCombinedSyncTest.Fixture.response(200, json);
		}
	}
}
