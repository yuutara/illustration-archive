package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
public class XLikePersistenceService {
	private final XLikeRepository items;
	private final XLikeMediaRepository media;
	private final XLikeSyncStateRepository syncState;

	public XLikePersistenceService(XLikeRepository items, XLikeMediaRepository media, XLikeSyncStateRepository syncState) {
		this.items = items;
		this.media = media;
		this.syncState = syncState;
	}

	/** A fetched page is saved atomically; no filesystem operation is involved. */
	@Transactional
	public XLikeSyncSummary savePage(XLikePage page) {
		return save(page, null, false, Set.of(), null);
	}

	@Transactional
	public XLikeSyncSummary saveOrderedPage(XLikePage page, long firstOrder, boolean history) {
		return save(page, firstOrder, history, Set.of(), null);
	}

	@Transactional
	public XLikeSyncSummary saveOrderedPage(XLikePage page, long firstOrder, boolean history, Set<String> previouslyOrdered) {
		return save(page, firstOrder, history, previouslyOrdered, null);
	}

	@Transactional
	public XLikeSyncSummary saveRecentPage(XLikePage page, XLikeSyncStateRepository.RecentState current,
			XLikeSyncStateRepository.RecentState next, Set<String> previouslyOrdered) {
		XLikeSyncSummary saved = save(page, current.orderNext(), false, previouslyOrdered, current.orderFloor());
		if (next != null) syncState.saveRecent(next);
		return saved;
	}

	private XLikeSyncSummary save(XLikePage page, Long firstOrder, boolean history, Set<String> previouslyOrdered, Long floor) {
		int added = 0;
		int existing = 0;
		int pending = 0;
		int unsupported = 0;
		int position = 0;
		Set<String> seen = new HashSet<>(previouslyOrdered);
		for (XLikeCandidate candidate : page.candidates()) {
			long order = firstOrder == null ? 0 : Math.subtractExact(firstOrder, position++);
			boolean firstObservation = seen.add(candidate.xPostId());
			Long itemId = firstOrder == null ? items.insertIfAbsent(candidate) : items.insertIfAbsent(candidate, order);
			XLikeStatus storedStatus;
			if (itemId == null) {
				existing++;
				storedStatus = items.findStatusByPostId(candidate.xPostId());
				if (firstOrder != null && firstObservation) {
					if (floor != null) items.updateRecentOrder(candidate.xPostId(), order, floor, firstOrder,
							Math.subtractExact(firstOrder, page.candidates().size() - 1));
					else items.updateLikesOrder(candidate.xPostId(), order, history);
				}
				Long reopened = storedStatus == XLikeStatus.UNSUPPORTED && candidate.status() == XLikeStatus.PENDING
						? items.reopenUnsupported(candidate.xPostId()) : null;
				if (reopened != null) {
					media.deleteForItem(reopened);
					for (XLikeMedia attachment : candidate.media()) media.insert(reopened, attachment);
					storedStatus = XLikeStatus.PENDING;
				}
			} else {
				added++;
				storedStatus = candidate.status();
				for (XLikeMedia attachment : candidate.media()) {
					media.insert(itemId, attachment);
				}
			}
			if (storedStatus == XLikeStatus.PENDING) {
				pending++;
			} else if (storedStatus == XLikeStatus.UNSUPPORTED) {
				unsupported++;
			}
		}
		return new XLikeSyncSummary(1, page.candidates().size(), added, existing,
				pending, unsupported, page.hasMore(), false, false);
	}
}
