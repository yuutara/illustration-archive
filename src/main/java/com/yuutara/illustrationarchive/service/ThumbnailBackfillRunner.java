package com.yuutara.illustrationarchive.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
		prefix = "illustration.maintenance",
		name = "thumbnail-backfill",
		havingValue = "true",
		matchIfMissing = false
)
public class ThumbnailBackfillRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(ThumbnailBackfillRunner.class);

	private final ThumbnailBackfillService thumbnailBackfillService;

	public ThumbnailBackfillRunner(ThumbnailBackfillService thumbnailBackfillService) {
		this.thumbnailBackfillService = thumbnailBackfillService;
	}

	@Override
	public void run(String... args) {
		List<ThumbnailBackfillResult> results = thumbnailBackfillService.backfillThumbnails();
		long ready = countByStatus(results, ThumbnailBackfillResult.READY);
		long skippedGif = countByStatus(results, ThumbnailBackfillResult.SKIPPED_GIF);
		long failed = countByStatus(results, ThumbnailBackfillResult.FAILED);

		log.info(
				"Thumbnail backfill completed. total={}, ready={}, skippedGif={}, failed={}",
				results.size(),
				ready,
				skippedGif,
				failed
		);
	}

	private long countByStatus(List<ThumbnailBackfillResult> results, String status) {
		return results.stream()
				.filter(result -> status.equals(result.status()))
				.count();
	}
}
