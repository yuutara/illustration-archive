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
		name = "sha256-backfill",
		havingValue = "true",
		matchIfMissing = false
)
public class Sha256BackfillRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(Sha256BackfillRunner.class);

	private final Sha256BackfillService sha256BackfillService;

	public Sha256BackfillRunner(Sha256BackfillService sha256BackfillService) {
		this.sha256BackfillService = sha256BackfillService;
	}

	@Override
	public void run(String... args) {
		List<Sha256BackfillResult> results = sha256BackfillService.backfillPendingSha256();
		long updated = countByStatus(results, Sha256BackfillResult.UPDATED);
		long duplicate = countByStatus(results, Sha256BackfillResult.DUPLICATE);
		long failed = countByStatus(results, Sha256BackfillResult.FAILED);

		log.info(
				"SHA-256 backfill completed. total={}, updated={}, duplicate={}, failed={}",
				results.size(),
				updated,
				duplicate,
				failed
		);

		for (Sha256BackfillResult result : results) {
			if (Sha256BackfillResult.DUPLICATE.equals(result.status())) {
				log.warn(
						"SHA-256 backfill duplicate. assetId={}, existingAssetId={}",
						result.assetId(),
						result.existingAssetId()
				);
			} else if (Sha256BackfillResult.FAILED.equals(result.status())) {
				log.error(
						"SHA-256 backfill failed. assetId={}, storageKey={}, errorMessage={}",
						result.assetId(),
						result.storageKey(),
						result.errorMessage()
				);
			}
		}
	}

	private long countByStatus(List<Sha256BackfillResult> results, String status) {
		return results.stream()
				.filter(result -> status.equals(result.status()))
				.count();
	}
}
