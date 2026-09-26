package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class XLikePersistenceService {
	private final XLikeRepository items;
	private final XLikeMediaRepository media;

	public XLikePersistenceService(XLikeRepository items, XLikeMediaRepository media) {
		this.items = items;
		this.media = media;
	}

	/** A fetched page is saved atomically; no filesystem operation is involved. */
	@Transactional
	public XLikeSyncSummary savePage(XLikePage page) {
		int added = 0;
		int existing = 0;
		int pending = 0;
		int unsupported = 0;
		for (XLikeCandidate candidate : page.candidates()) {
			Long itemId = items.insertIfAbsent(candidate);
			XLikeStatus storedStatus;
			if (itemId == null) {
				existing++;
				storedStatus = items.findStatusByPostId(candidate.xPostId());
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
		return new XLikeSyncSummary(page.candidates().size(), added, existing,
				pending, unsupported, page.hasMore());
	}
}
