package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class XLikeSyncService {
	private final XApiClient client;
	private final XLikePersistenceService persistence;
	private final XLikeRepository items;
	private final XLikeMediaRepository media;
	private final int defaultPageSize;

	public XLikeSyncService(XApiClient client, XLikePersistenceService persistence,
			XLikeRepository items, XLikeMediaRepository media,
			@Value("${x.api.default-page-size:5}") int defaultPageSize) {
		this.client = client;
		this.persistence = persistence;
		this.items = items;
		this.media = media;
		this.defaultPageSize = defaultPageSize;
	}

	public XLikeSyncSummary syncRecent(Integer maxResults) {
		int size = maxResults == null ? defaultPageSize : maxResults;
		if (size < 5 || size > 100) {
			throw new IllegalArgumentException("maxResults must be between 5 and 100.");
		}
		return persistence.savePage(client.fetchRecentLikes(size));
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
