package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetThumbnailBackfillCandidate;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ThumbnailBackfillService {

	private static final Logger log = LoggerFactory.getLogger(ThumbnailBackfillService.class);

	private final AssetRepository assetRepository;
	private final ThumbnailService thumbnailService;

	public ThumbnailBackfillService(AssetRepository assetRepository, ThumbnailService thumbnailService) {
		this.assetRepository = assetRepository;
		this.thumbnailService = thumbnailService;
	}

	public List<ThumbnailBackfillResult> backfillThumbnails() {
		List<AssetThumbnailBackfillCandidate> candidates = assetRepository.findThumbnailBackfillCandidates();
		List<ThumbnailBackfillResult> results = new ArrayList<>(candidates.size());

		for (AssetThumbnailBackfillCandidate candidate : candidates) {
			if ("image/gif".equals(candidate.mimeType())) {
				results.add(ThumbnailBackfillResult.skippedGif(candidate.assetId(), candidate.storageKey()));
				continue;
			}

			try {
				if (!"image/jpeg".equals(candidate.mimeType()) && !"image/png".equals(candidate.mimeType())) {
					throw new IllegalArgumentException("Unsupported MIME type for thumbnail backfill.");
				}
				thumbnailService.generateThumbnail(candidate.storageKey());
				results.add(ThumbnailBackfillResult.ready(candidate.assetId(), candidate.storageKey()));
			} catch (RuntimeException exception) {
				log.error(
						"Thumbnail backfill failed. assetId={}, storageKey={}, mimeType={}",
						candidate.assetId(),
						candidate.storageKey(),
						candidate.mimeType(),
						exception
				);
				results.add(ThumbnailBackfillResult.failure(
						candidate.assetId(),
						candidate.storageKey(),
						exception.getMessage()
				));
			}
		}

		return List.copyOf(results);
	}
}
