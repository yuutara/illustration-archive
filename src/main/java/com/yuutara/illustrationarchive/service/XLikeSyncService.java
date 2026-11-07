package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeInboxPage;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.dto.XLikeSyncReport;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Service
public class XLikeSyncService {
	private static final Logger log = LoggerFactory.getLogger(XLikeSyncService.class);
	private final XApiClient client;
	private final XLikePersistenceService persistence;
	private final XLikeRepository items;
	private final XLikeMediaRepository media;
	private final XLikeSyncStateRepository syncState;
	private final int defaultPageSize;
	private final int defaultMaxPages;
	private final XLikeSyncBudget budget;
	private IntegrationEvidence evidence = new IntegrationEvidence() { };
	private XLikeSyncReadLimit readLimit = () -> null;

	public XLikeSyncService(XApiClient client, XLikePersistenceService persistence,
			XLikeRepository items, XLikeMediaRepository media, XLikeSyncStateRepository syncState,
			@Value("${x.api.default-page-size:5}") int defaultPageSize,
			@Value("${x.api.default-max-pages:3}") int defaultMaxPages, XLikeSyncBudget budget) {
		this.client = client;
		this.persistence = persistence;
		this.items = items;
		this.media = media;
		this.syncState = syncState;
		this.defaultPageSize = defaultPageSize;
		this.defaultMaxPages = defaultMaxPages;
		this.budget = budget;
	}

	@Autowired(required = false)
	public void setEvidence(IntegrationEvidence evidence) {
		this.evidence = evidence;
	}

	@Autowired(required = false)
	public void setReadLimit(XLikeSyncReadLimit readLimit) {
		this.readLimit = readLimit;
	}

	/** The configured budget, with the user's read limit when there is one. Read once per sync. */
	public XLikeSyncBudget currentBudget() {
		Integer reads = readLimit.reads();
		return reads == null ? budget : budget.withReadLimit(reads);
	}

	/** Recent owns its resumable frontier; History keeps its independent older cursor. */
	public synchronized XLikeSyncReport sync() {
		var usage = new XLikeSyncBudget.Usage(currentBudget());
		Scan recent = new Scan(), history = new Scan();
		String attempt = evidence.xAttempt();
		XApiClient.XUser reader = null;
		try {
			XApiClient.XUser user = client.resolveUser();
			if (!ownsProgress(user)) {
				recent.reason = "ACCOUNT_MISMATCH"; recent.partial = true; history.reason = "SKIPPED_AFTER_FAILURE";
				return report(recent, history, usage.limits);
			}
			reader = user;
			String userId = user.id();
			recent = catchUp(userId, usage);
			var state = syncState.load();
			if (recent.partial) history.reason = "SKIPPED_AFTER_FAILURE";
			else if (state.isEmpty()) history.reason = "WAIT_RECENT_BOOTSTRAP";
			else if ("COMPLETE".equals(state.get().status())) history.reason = "HISTORY_COMPLETE";
			else if (!validHistory(state.get())) {
				syncState.markInvalid(state.get().maxResults());
				history.reason = "INVALID_TOKEN"; history.partial = true; history.hasMore = true;
			} else history = scan(userId, state.get().maxResults(), state.get().nextToken(),
					usage.limits.maxPages(), true, usage, true);
		} catch (XApiException failure) {
			recent.reason = "API_FAILURE"; recent.partial = true; recent.scanKnown = false;
			recent.upstreamStatus = failure.upstreamStatus(); history.reason = "SKIPPED_AFTER_FAILURE";
		} catch (RuntimeException failure) {
			log.warn("X Likes sync state could not be read or saved.", failure);
			history.reason = "STATE_FAILURE"; history.partial = true;
		}
		XLikeSyncReport finished = report(recent, history, usage.limits);
		if (reader != null && !finished.partial()) evidence.xWorked(attempt, reader.username());
		return finished;
	}

	private XLikeSyncReport report(Scan recent, Scan history, XLikeSyncBudget limits) {
		var progress = progress();
		return new XLikeSyncReport(recent.report(), history.report(), limits.maxPages(), limits.maxScanned(),
				limits.maxNew(), recent.partial || history.partial, progress.historyStatus(), progress.recentStatus());
	}

	/**
	 * Cursors, anchor windows and completion marks all describe one account's Likes, so nobody else may continue
	 * them. The check uses the /users/me read a sync makes anyway, which is why signing in stays free of reads. A
	 * refused account changes nothing but the note of who was refused. Progress with no recorded owner refuses
	 * everyone: it may well be this account's, but only the user can know that.
	 */
	private boolean ownsProgress(XApiClient.XUser user) {
		String owner = syncState.loadAccount().map(XLikeSyncStateRepository.Account::ownerId).orElse(null);
		if (!user.id().equals(owner) && syncState.hasProgress()) {
			syncState.observeAccount(user.id(), user.username());
			return false;
		}
		syncState.bindAccount(user.id());
		return true;
	}

	/** The user says progress with no recorded owner is this account's. A recorded owner cannot be overruled this way. */
	public synchronized void adoptProgress(String confirmation) {
		var seen = confirmed(confirmation);
		if (seen.ownerId() != null) throw new IllegalArgumentException("This X Likes progress already has an owner.");
		if (!syncState.settleAccount(seen)) throw new XSyncConfirmationExpiredException();
	}

	/**
	 * Drops the saved progress for the account a sync refused, once the user has confirmed it. Only progress goes:
	 * the Inbox, works and media stay, X is not contacted, and scanning waits for the next sync the user starts.
	 */
	public synchronized void restartForRefusedAccount(String confirmation) {
		if (!syncState.restartFor(confirmed(confirmation))) throw new XSyncConfirmationExpiredException();
	}

	/**
	 * A confirmation answers one question about one account. Another window may have changed that question since
	 * the page was drawn, so it only counts while the saved state is still exactly what the page showed. Comparing
	 * here, under the lock every sync holds, also makes it wait for a sync already running.
	 */
	private XLikeSyncStateRepository.Account confirmed(String confirmation) {
		return syncState.loadAccount().filter(a -> a.observedId() != null && a.confirmation().equals(confirmation))
				.orElseThrow(XSyncConfirmationExpiredException::new);
	}

	/** A persisted ordered window is stronger evidence than membership of one already-saved Post. */
	private Scan catchUp(String userId, XLikeSyncBudget.Usage usage) {
		Scan total = new Scan();
		int initialPages = usage.pages, initialReserved = usage.reserved;
		try {
			var saved = syncState.loadRecent();
			var current = saved.orElse(null);
			if (current != null && !validRecent(current)) {
				syncState.saveRecent(invalidRecent(current));
				total.reason = "INVALID_TOKEN"; total.partial = true; total.hasMore = true;
				return total;
			}
			if (current == null) {
				current = newRecent(null, budget.recentPageSize(), false);
				syncState.restartRecent(current);
			} else if (!"ACTIVE".equals(current.status())) {
				// VERIFY is deliberately deferred to a later click: catch changes across requests/restarts.
				Scan probe = scan(userId, current.maxResults(), null, 1, false, usage, true);
				total.add(probe);
				if (probe.partial || probe.firstPage == null) return total;
				List<String> observed = postIds(probe.firstPage);
				List<String> expected = "VERIFY".equals(current.status()) ? current.headIds() : current.anchorIds();
				if (observed.equals(expected) && (trusted(expected) || !probe.firstPage.hasMore())) {
					finishRecent(current, observed);
					total.reason = "CAUGHT_UP"; total.hasMore = false;
					return total;
				}
				// A changed head starts a bounded follow-up sweep back to the completed coverage window.
				current = newRecent(expected, budget.recentPageSize(), "VERIFY".equals(current.status()) && current.coverageToEnd());
				// The probe was fetched with the old size. Preserve it until this sweep finishes.
				current = new XLikeSyncStateRepository.RecentState(null, saved.get().maxResults(), current.status(),
						current.anchorIds(), null, 0, current.orderNext(), current.orderFloor(), current.coverageToEnd());
				syncState.restartRecent(current);
				var next = recentStep(current, probe.firstPage);
				// Re-rank and checkpoint the already-saved probe atomically; do not count it twice.
				persistence.saveRecentPage(probe.firstPage, current, next, Set.of());
				current = next;
				if (!"ACTIVE".equals(current.status())) {
					completeRecentScan(total, current); return total;
				}
				if (usage.limits.recentMaxPages() == 1) {
					String stop = usage.stopBefore(current.maxResults());
					total.reason = stop == null ? "RECENT_WINDOW" : stop; total.hasMore = true; return total;
				}
				Scan tail = scan(userId, current.maxResults(), current.nextToken(), usage.limits.recentMaxPages() - 1,
						false, usage, true, current);
				total.add(tail); return total;
			} else if (current.headIds() != null && usage.limits.maxPages() - usage.pages >= 2
					&& usage.limits.maxScanned() - usage.reserved >= 2 * current.maxResults()
					&& usage.limits.maxNew() - usage.added >= 2 * current.maxResults()) {
				// Preserve at least one resume slot; tiny budgets prefer progress over repeated head probes.
				Scan probe = scan(userId, current.maxResults(), null, 1, false, usage, true);
				total.add(probe);
				if (probe.partial) return total;
			}
			Scan tail = scan(userId, current.maxResults(), current.nextToken(), usage.limits.recentMaxPages(), false, usage, true, current);
			total.add(tail);
		} catch (RuntimeException failure) {
			log.warn("X Likes recent checkpoint could not be read or saved.", failure);
			total.reason = "STATE_FAILURE"; total.partial = true; total.hasMore = true;
		} finally {
			total.requested = usage.pages - initialPages; total.reserved = usage.reserved - initialReserved;
		}
		return total;
	}

	private XLikeSyncStateRepository.RecentState newRecent(List<String> anchor, int size, boolean toEnd) {
		long floor = items.highestLikesOrder();
		return new XLikeSyncStateRepository.RecentState(null, size, "ACTIVE", anchor, null, 0,
				Math.addExact(floor, 1L << 32), floor, toEnd);
	}

	private static boolean validRecent(XLikeSyncStateRepository.RecentState state) {
		return state.status() != null && Set.of("ACTIVE", "VERIFY", "CAUGHT_UP").contains(state.status())
				&& state.maxResults() >= 5 && state.maxResults() <= 100 && state.orderNext() > state.orderFloor()
				&& state.matched() >= 0 && (state.anchorIds() == null || state.matched() <= state.anchorIds().size())
				&& (!"ACTIVE".equals(state.status()) || !trusted(state.anchorIds()) || state.matched() < state.anchorIds().size())
				&& (!"ACTIVE".equals(state.status()) || state.headIds() == null || (state.nextToken() != null && !state.nextToken().isBlank()));
	}

	private static XLikeSyncStateRepository.RecentState invalidRecent(XLikeSyncStateRepository.RecentState s) {
		return new XLikeSyncStateRepository.RecentState(s.nextToken(), s.maxResults(), "INVALID", s.anchorIds(),
				s.headIds(), s.matched(), s.orderNext(), s.orderFloor(), s.coverageToEnd());
	}

	private XLikeSyncStateRepository.RecentState recentStep(XLikeSyncStateRepository.RecentState current, XLikePage page) {
		if (page.upstreamErrorCount() > 0) return null; // Data may commit; this page must be retried.
		List<String> head = current.headIds() == null ? postIds(page) : current.headIds();
		int matched = current.matched();
		boolean joined = false;
		if (trusted(current.anchorIds())) {
			for (String id : postIds(page)) {
				if (id.equals(current.anchorIds().get(matched))) matched++;
				else matched = id.equals(current.anchorIds().get(0)) ? 1 : 0;
				if (matched == current.anchorIds().size()) { joined = true; break; }
			}
		}
		String status = (!page.hasMore() || joined) ? (current.headIds() == null ? "CAUGHT_UP" : "VERIFY") : "ACTIVE";
		if ("ACTIVE".equals(status) && (page.nextToken() == null || page.nextToken().isBlank()
				|| page.nextToken().equals(current.nextToken()) || syncState.recentHasSeen(page.nextToken()))) status = "INVALID";
		return new XLikeSyncStateRepository.RecentState(page.nextToken(), current.maxResults(), status,
				"CAUGHT_UP".equals(status) ? head : current.anchorIds(), head, "CAUGHT_UP".equals(status) ? 0 : matched,
				Math.subtractExact(current.orderNext(), page.candidates().size()), current.orderFloor(),
				current.coverageToEnd() || !page.hasMore());
	}

	private static List<String> postIds(XLikePage page) { return page.candidates().stream().map(c -> c.xPostId()).toList(); }
	private static boolean trusted(List<String> ids) { return ids != null && new HashSet<>(ids).size() >= 2; }

	private void finishRecent(XLikeSyncStateRepository.RecentState current, List<String> head) {
		syncState.saveRecent(new XLikeSyncStateRepository.RecentState(null, current.maxResults(), "CAUGHT_UP", head,
				head, 0, current.orderNext(), current.orderFloor(), current.coverageToEnd()));
		if (current.coverageToEnd()) syncState.markComplete(current.maxResults());
	}

	private void completeRecentScan(Scan result, XLikeSyncStateRepository.RecentState current) {
		result.hasMore = !"CAUGHT_UP".equals(current.status());
		result.reason = switch (current.status()) {
			case "CAUGHT_UP" -> "CAUGHT_UP";
			case "VERIFY" -> "VERIFY_PENDING";
			default -> "INVALID_TOKEN";
		};
		if ("CAUGHT_UP".equals(current.status())) finishRecent(current, current.headIds());
		if ("INVALID".equals(current.status())) result.partial = true;
	}

	public XLikeSyncReport.Progress progress() {
		String recent = syncState.loadRecent().map(s -> validRecent(s) ? s.status() : "INVALID").orElse("UNKNOWN");
		String history = syncState.load().map(s -> !validHistory(s) ? "INVALID"
				: "COMPLETE".equals(s.status()) ? "COMPLETE" : "MORE").orElse("UNKNOWN");
		var refused = syncState.loadAccount().filter(a -> a.observedId() != null);
		return new XLikeSyncReport.Progress(recent, history, !"CAUGHT_UP".equals(recent) || !"COMPLETE".equals(history),
				refused.map(a -> a.ownerId() == null ? "UNATTRIBUTED" : "CHANGED").orElse("OK"),
				refused.map(a -> a.observedUsername() == null ? "ID " + a.observedId() : "@" + a.observedUsername()).orElse(null),
				refused.map(XLikeSyncStateRepository.Account::observedId).orElse(null),
				refused.map(XLikeSyncStateRepository.Account::confirmation).orElse(null));
	}

	private static boolean validHistory(XLikeSyncStateRepository.State state) {
		return "COMPLETE".equals(state.status()) || ("ACTIVE".equals(state.status()) && state.nextToken() != null
				&& !state.nextToken().isBlank() && state.maxResults() >= 5 && state.maxResults() <= 100);
	}

	public synchronized void resetInvalidRecent() {
		var current = syncState.loadRecent().orElseThrow(() -> new IllegalArgumentException("There is no Recent progress to reset."));
		if (validRecent(current)) throw new IllegalArgumentException("Recent progress is not invalid.");
		syncState.restartRecent(newRecent(current.anchorIds(), budget.recentPageSize(), false));
	}

	public synchronized XLikeSyncSummary syncRecent(Integer maxResults, Integer maxPages) {
		return syncPages(maxResults, maxPages, false);
	}

	public synchronized XLikeSyncSummary syncContinuation(Integer maxResults, Integer maxPages) {
		return syncPages(maxResults, maxPages, true);
	}

	private XLikeSyncSummary syncPages(Integer maxResults, Integer maxPages, boolean continuationMode) {
		int size = maxResults == null ? defaultPageSize : maxResults;
		int limit = maxPages == null ? defaultMaxPages : maxPages;
		if (size < 5 || size > 100) {
			throw new IllegalArgumentException("maxResults must be between 5 and 100.");
		}
		if (limit < 1 || limit > 10) {
			throw new IllegalArgumentException("maxPages must be between 1 and 10.");
		}
		Optional<XLikeSyncStateRepository.State> continuation = continuationMode ? syncState.load() : Optional.empty();
		boolean complete = continuation.filter(state -> "COMPLETE".equals(state.status())).isPresent();
		if (continuation.isPresent() && "INVALID".equals(continuation.get().status())) {
			throw new XApiException("X Likes continuation is invalid. Reset it explicitly before continuing history.", null);
		}
		if (!complete && continuation.isPresent() && continuation.get().maxResults() != size) {
			throw new IllegalArgumentException("Finish the saved continuation with maxResults="
					+ continuation.get().maxResults() + " before changing maxResults.");
		}
		String token = complete ? null : continuation.map(XLikeSyncStateRepository.State::nextToken).orElse(null);
		if (!complete && continuation.isPresent() && (token == null || token.isBlank())) {
			syncState.markInvalid(size);
			throw new XApiException("Saved X Likes continuation has no token. Reset it explicitly before continuing history.", null);
		}
		XApiClient.XUser user = client.resolveUser();
		// Recent mode neither reads nor writes saved progress; this mode continues it, and "complete" is part of it.
		if (continuationMode && !ownsProgress(user)) {
			throw new XApiException("The saved X Likes progress does not belong to this account.", null);
		}
		if (complete) return new XLikeSyncSummary(0, 0, 0, 0, 0, 0, false, false, false);
		var limits = new XLikeSyncBudget(size, limit, limit, size * limit, size * limit);
		Scan result = scan(user.id(), size, token, limit, continuationMode, new XLikeSyncBudget.Usage(limits), false);
		return new XLikeSyncSummary(result.saved, result.savedFetched, result.added, result.existing,
				result.pending, result.unsupported, result.hasMore,
				result.hasMore && result.saved == limit && !"INVALID_TOKEN".equals(result.reason),
				"INVALID_TOKEN".equals(result.reason));
	}

	/** Shared page engine for the combined button and the explicit Recent/History endpoints. */
	private Scan scan(String userId, int size, String token, int phasePages, boolean history,
			XLikeSyncBudget.Usage usage, boolean reportFailures) {
		return scan(userId, size, token, phasePages, history, usage, reportFailures, null);
	}

	private Scan scan(String userId, int size, String token, int phasePages, boolean history,
			XLikeSyncBudget.Usage usage, boolean reportFailures, XLikeSyncStateRepository.RecentState checkpoint) {
		Scan result = new Scan();
		result.nextToken = token;
		result.hasMore = token != null;
		int initialPages = usage.pages;
		int initialReserved = usage.reserved;
		long order = checkpoint != null ? checkpoint.orderNext() : history ? Math.subtractExact(items.lowestLikesOrder(), 1)
				: Math.addExact(items.highestLikesOrder(), usage.limits.maxScanned());
		Set<String> seenTokens = new HashSet<>();
		Set<String> orderedPosts = new HashSet<>();
		try {
			while (result.saved < phasePages) {
				if (checkpoint != null && checkpoint.orderNext() - checkpoint.orderFloor() < size) {
					syncState.saveRecent(invalidRecent(checkpoint)); result.reason = "INVALID_CHECKPOINT";
					result.partial = true; break;
				}
				String stop = usage.stopBefore(size);
				if (stop != null) { result.reason = stop; break; }
				XLikePage page;
				try {
					if (reportFailures) {
						page = client.fetchRecentLikes(userId, size, token, () -> usage.reserve(size));
					} else {
						usage.reserve(size);
						page = fetchPage(userId, size, token, history);
					}
				} catch (XLikeSyncBudget.LimitReached failure) {
					result.reason = failure.reason;
					result.partial = true;
					result.scanKnown = false;
					break;
				} catch (XApiException failure) {
					if (!reportFailures) throw failure;
					if (failure.scannedCount() == null) result.scanKnown = false;
					else result.scanned += failure.scannedCount();
					result.upstreamStatus = failure.upstreamStatus();
					result.reason = token != null && Integer.valueOf(400).equals(failure.upstreamStatus())
							? "INVALID_TOKEN" : "API_FAILURE";
					if (history && "INVALID_TOKEN".equals(result.reason)) syncState.markInvalid(size);
					if (checkpoint != null && "INVALID_TOKEN".equals(result.reason)) syncState.saveRecent(invalidRecent(checkpoint));
					result.partial = true;
					break;
				}
				result.scanned += page.candidates().size();
				if (result.firstPage == null) result.firstPage = page;
				var nextCheckpoint = checkpoint == null ? null : recentStep(checkpoint, page);
				XLikeSyncSummary saved;
				try { saved = checkpoint == null ? persistence.saveOrderedPage(page, order, history, orderedPosts)
						: persistence.saveRecentPage(page, checkpoint, nextCheckpoint, orderedPosts); }
				catch (RuntimeException failure) {
					if (!reportFailures) throw failure;
					log.warn("X Likes page could not be saved.", failure);
					result.reason = "SAVE_FAILURE";
					result.partial = true;
					break;
				}
				result.saved++;
				page.candidates().forEach(candidate -> orderedPosts.add(candidate.xPostId()));
				result.savedFetched += saved.fetchedCount();
				result.added += saved.newCount();
				usage.added += saved.newCount();
				result.existing += saved.existingCount();
				result.pending += saved.pendingCount();
				result.unsupported += saved.unsupportedCount();
				order = Math.subtractExact(order, page.candidates().size());
				if (page.upstreamErrorCount() > 0) {
					// Keep valid records, but retry this page rather than stepping over omitted records.
					if (!reportFailures) throw new XApiException("X returned a partial Likes page.", 200);
					result.reason = "API_PARTIAL";
					result.hasMore = true;
					result.partial = true;
					break;
				}
				if (checkpoint != null) {
					checkpoint = nextCheckpoint;
					if (!"ACTIVE".equals(checkpoint.status())) { completeRecentScan(result, checkpoint); break; }
					token = checkpoint.nextToken(); result.nextToken = token;
					result.hasMore = true;
					if (result.saved == phasePages) {
						String reached = usage.stopBefore(size);
						result.reason = reached == null ? "RECENT_WINDOW" : reached;
					}
					continue;
				}
				result.hasMore = page.hasMore();
				if (!result.hasMore) {
					if (history) { if (reportFailures) syncState.markComplete(size); else syncState.clear(); }
					result.reason = "NATURAL_END";
					break;
				}
				String next = page.nextToken();
				if (next == null || next.isBlank() || !seenTokens.add(next) || next.equals(token)
						|| (history && syncState.hasSeen(next))) {
					if (history) syncState.markInvalid(size);
					result.reason = "INVALID_TOKEN";
					result.partial = true;
					break;
				}
				if (history) syncState.advance(next, size);
				token = next;
				result.nextToken = next;
				if (result.saved == phasePages) result.reason = history ? "PAGE_BUDGET" : "RECENT_WINDOW";
			}
		} catch (RuntimeException failure) {
			if (!reportFailures) throw failure;
			result.reason = "STATE_FAILURE";
			result.partial = true;
		} finally {
			result.requested = usage.pages - initialPages;
			result.reserved = usage.reserved - initialReserved;
		}
		return result;
	}

	private static final class Scan {
		int requested, reserved, saved, savedFetched, scanned, added, existing, pending, unsupported;
		boolean scanKnown = true, hasMore, partial;
		String reason = "NOT_STARTED", nextToken;
		XLikePage firstPage;
		Integer upstreamStatus;
		void add(Scan other) {
			saved += other.saved; savedFetched += other.savedFetched; scanned += other.scanned;
			added += other.added; existing += other.existing; pending += other.pending; unsupported += other.unsupported;
			scanKnown &= other.scanKnown; partial |= other.partial; hasMore = other.hasMore;
			reason = other.reason; upstreamStatus = other.upstreamStatus;
		}
		XLikeSyncReport.Phase report() {
			return new XLikeSyncReport.Phase(requested, saved, scanned, reserved, added, existing,
					savedFetched - pending - unsupported, unsupported, scanKnown, hasMore, reason, upstreamStatus);
		}
	}

	private XLikePage fetchPage(String userId, int size, String token, boolean continuationMode) {
		try {
			return client.fetchRecentLikes(userId, size, token);
		} catch (XApiException e) {
			if (continuationMode && token != null && Integer.valueOf(400).equals(e.upstreamStatus())) {
				syncState.markInvalid(size);
				throw new XApiException("X rejected the saved pagination token. Reset the continuation explicitly; the Likes gap may be incomplete.", 400);
			}
			throw e;
		}
	}

	@Transactional
	public synchronized void resetInvalidContinuation() {
		if (syncState.load().filter(state -> !validHistory(state)).isEmpty()) {
			throw new IllegalArgumentException("There is no invalid X Likes continuation to reset.");
		}
		syncState.clear();
		// The old History cursor is gone. Rebuild from the head to the natural end;
		// a previously caught-up Recent anchor alone cannot establish the missing old range.
		syncState.restartRecent(newRecent(null, budget.recentPageSize(), false));
	}

	public XLikeInboxPage inbox(Integer requestedPage, Integer requestedSize) {
		return page(requestedPage, requestedSize, items::countPending, items::findPendingPage, this::progress);
	}

	/** Skipped items, most recently skipped first, so a skip made by mistake is found at the top. */
	public XLikeInboxPage skipped(Integer requestedPage, Integer requestedSize) {
		return page(requestedPage, requestedSize, items::countSkipped, items::findSkippedPage, () -> null);
	}

	private interface PageQuery {
		List<XLikeInboxItem> find(int size, long offset);
	}

	/** Validates before touching the database, so a bad request reads nothing. */
	private XLikeInboxPage page(Integer requestedPage, Integer requestedSize, LongSupplier count, PageQuery query,
			Supplier<XLikeSyncReport.Progress> progress) {
		int page = requestedPage == null ? 0 : requestedPage;
		int size = requestedSize == null ? 24 : requestedSize;
		if (page < 0) throw new IllegalArgumentException("page must be greater than or equal to 0.");
		if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100.");
		long totalItems = count.getAsLong();
		int totalPages = Math.toIntExact(totalItems == 0 ? 0 : (totalItems - 1) / size + 1);
		List<XLikeInboxItem> found = page >= totalPages ? List.of() : query.find(size, (long) page * size);
		Map<Long, List<XLikeMedia>> byItem = new HashMap<>();
		if (!found.isEmpty()) {
			for (XLikeMediaRepository.PendingMedia row : media.findForItemIds(found.stream().map(XLikeInboxItem::id).toList())) {
				byItem.computeIfAbsent(row.itemId(), ignored -> new ArrayList<>()).add(row.media());
			}
		}
		List<XLikeInboxItem> pageItems = found.stream().map(item -> new XLikeInboxItem(item.id(), item.xPostId(),
				item.authorDisplayName(), item.authorUsername(), item.postText(),
				item.postCreatedAt(), item.discoveredAt(),
				List.copyOf(byItem.getOrDefault(item.id(), List.of())))).toList();
		return new XLikeInboxPage(pageItems, page, size, totalItems, totalPages, progress.get());
	}

	@Transactional
	public SkipSummary skip(List<Long> itemIds) {
		if (itemIds == null || itemIds.isEmpty() || itemIds.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new IllegalArgumentException("itemIds must contain at least one positive item id.");
		}
		int skipped = 0;
		for (long itemId : itemIds.stream().distinct().toList()) {
			skipped += items.skipIfPending(itemId);
		}
		return new SkipSummary(itemIds.size(), skipped);
	}

	/**
	 * Undo for a skip: skipped items go back to Pending. Only SKIPPED rows change, so an id sent twice, or one that
	 * was put back elsewhere in the meantime, counts once and nothing else is touched.
	 */
	@Transactional
	public UnskipSummary unskip(List<Long> itemIds) {
		if (itemIds == null || itemIds.isEmpty() || itemIds.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new IllegalArgumentException("itemIds must contain at least one positive item id.");
		}
		int restored = 0;
		for (long itemId : itemIds.stream().distinct().toList()) {
			restored += items.unskipIfSkipped(itemId);
		}
		return new UnskipSummary(itemIds.size(), restored);
	}

	public record SkipSummary(int requestedCount, int skippedCount) {
	}

	public record UnskipSummary(int requestedCount, int restoredCount) {
	}
}
