package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetSha256BackfillCandidate;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class Sha256BackfillService {

	private static final Logger log = LoggerFactory.getLogger(Sha256BackfillService.class);

	private final AssetRepository assetRepository;
	private final FileStorageService fileStorageService;

	public Sha256BackfillService(
			AssetRepository assetRepository,
			FileStorageService fileStorageService
	) {
		this.assetRepository = assetRepository;
		this.fileStorageService = fileStorageService;
	}

	public List<Sha256BackfillResult> calculatePendingHashes() {
		List<AssetSha256BackfillCandidate> candidates = assetRepository.findSha256BackfillCandidates();
		List<Sha256BackfillResult> results = new ArrayList<>(candidates.size());

		for (AssetSha256BackfillCandidate candidate : candidates) {
			try {
				String sha256 = fileStorageService.calculateSha256(candidate.storageKey());
				results.add(Sha256BackfillResult.success(candidate.assetId(), candidate.storageKey(), sha256));
			} catch (FileStorageException | FileStorageValidationException exception) {
				log.error(
						"Failed to calculate SHA-256 for historical asset. assetId={}, storageKey={}",
						candidate.assetId(),
						candidate.storageKey(),
						exception
				);
				results.add(Sha256BackfillResult.failure(
						candidate.assetId(),
						candidate.storageKey(),
						safeErrorMessage(exception)
				));
			}
		}

		return List.copyOf(results);
	}

	private String safeErrorMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank()
				? exception.getClass().getSimpleName()
				: message;
	}
}
