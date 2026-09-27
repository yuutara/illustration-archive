package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class XLikeSyncService {
	private final XApiClient client;
	private final XLikePersistenceService persistence;
	private final XLikeRepository items;
	private final XLikeMediaRepository media;
	private final XLikeSyncStateRepository syncState;
	private final int defaultPageSize;
	private final int defaultMaxPages;

	public XLikeSyncService(XApiClient client, XLikePersistenceService persistence,
			XLikeRepository items, XLikeMediaRepository media, XLikeSyncStateRepository syncState,
			@Value("${x.api.default-page-size:5}") int defaultPageSize,
			@Value("${x.api.default-max-pages:3}") int defaultMaxPages) {
		this.client = client;
		this.persistence = persistence;
		this.items = items;
		this.media = media;
		this.syncState = syncState;
		this.defaultPageSize = defaultPageSize;
		this.defaultMaxPages = defaultMaxPages;
	}

	public synchronized XLikeSyncSummary syncRecent(Integer maxResults, Integer maxPages) {
		int size = maxResults == null ? defaultPageSize : maxResults;
		int limit = maxPages == null ? defaultMaxPages : maxPages;
		if (size < 5 || size > 100) {
			throw new IllegalArgumentException("maxResults must be between 5 and 100.");
		}
		if (limit < 1 || limit > 10) {
			throw new IllegalArgumentException("maxPages must be between 1 and 10.");
		}
		var continuation = syncState.load();
		if (continuation.isPresent() && "INVALID".equals(continuation.get().status())) {
			throw new XApiException("X Likes continuation is invalid. Reset it explicitly before syncing latest Likes.", null);
		}
		if (continuation.isPresent() && continuation.get().maxResults() != size) {
			throw new IllegalArgumentException("Finish the saved continuation with maxResults="
					+ continuation.get().maxResults() + " before changing maxResults.");
		}
		String token = continuation.map(XLikeSyncStateRepository.State::nextToken).orElse(null);
		if (continuation.isPresent() && (token == null || token.isBlank())) {
			syncState.markInvalid(size);
			throw new XApiException("Saved X Likes continuation has no token. Reset it explicitly before syncing latest Likes.", null);
		}
		String userId = client.resolveUserId();
		int pagesFetched = 0;
		int fetched = 0;
		int added = 0;
		int existing = 0;
		int pending = 0;
		int unsupported = 0;
		boolean hasMore = false;
		boolean stoppedByMaxPages = false;
		boolean stoppedByInvalidToken = false;
		Set<String> seenTokens = new HashSet<>();
		while (pagesFetched < limit) {
			var page = fetchPage(userId, size, token);
			XLikeSyncSummary saved = persistence.savePage(page);
			pagesFetched++;
			fetched += saved.fetchedCount();
			added += saved.newCount();
			existing += saved.existingCount();
			pending += saved.pendingCount();
			unsupported += saved.unsupportedCount();
			hasMore = page.hasMore();
			if (!hasMore) {
				syncState.clear();
				break;
			}
			String nextToken = page.nextToken();
			if (nextToken == null || nextToken.isBlank() || !seenTokens.add(nextToken)
					|| syncState.hasSeen(nextToken)) {
				syncState.markInvalid(size);
				stoppedByInvalidToken = true;
				break;
			}
			syncState.advance(nextToken, size);
			token = nextToken;
			if (pagesFetched == limit) {
				stoppedByMaxPages = true;
				break;
			}
		}
		return new XLikeSyncSummary(pagesFetched, fetched, added, existing,
				pending, unsupported, hasMore, stoppedByMaxPages, stoppedByInvalidToken);
	}

	private XLikePage fetchPage(String userId, int size, String token) {
		try {
			return client.fetchRecentLikes(userId, size, token);
		} catch (XApiException e) {
			if (token != null && Integer.valueOf(400).equals(e.upstreamStatus())) {
				syncState.markInvalid(size);
				throw new XApiException("X rejected the saved pagination token. Reset the continuation explicitly; the Likes gap may be incomplete.", 400);
			}
			throw e;
		}
	}

	public synchronized void resetInvalidContinuation() {
		if (syncState.load().filter(state -> "INVALID".equals(state.status())).isEmpty()) {
			throw new IllegalArgumentException("There is no invalid X Likes continuation to reset.");
		}
		syncState.clear();
	}

	public List<XLikeInboxItem> inbox() {
		List<XLikeInboxItem> pending = items.findPending();
		Map<Long, List<XLikeMedia>> byItem = new HashMap<>();
		for (XLikeMediaRepository.PendingMedia row : media.findForPendingItems()) {
			byItem.computeIfAbsent(row.itemId(), ignored -> new ArrayList<>()).add(row.media());
		}
		return pending.stream().map(item -> new XLikeInboxItem(item.id(), item.xPostId(),
				item.authorDisplayName(), item.authorUsername(), item.postText(),
				item.postCreatedAt(), List.copyOf(byItem.getOrDefault(item.id(), List.of())))).toList();
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

	public record SkipSummary(int requestedCount, int skippedCount) {
	}
}
