package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetSha256BackfillCandidate;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

	public List<Sha256BackfillResult> backfillPendingSha256() {
		List<AssetSha256BackfillCandidate> candidates = assetRepository.findSha256BackfillCandidates();
		List<Sha256BackfillResult> results = new ArrayList<>(candidates.size());

		for (AssetSha256BackfillCandidate candidate : candidates) {
			try {
				String sha256 = fileStorageService.calculateSha256(candidate.storageKey());
				Optional<Long> existingAssetId = assetRepository.findIdBySha256(sha256);
				if (existingAssetId.isPresent()
						&& existingAssetId.get().longValue() != candidate.assetId()) {
					results.add(Sha256BackfillResult.duplicate(
							candidate.assetId(),
							candidate.storageKey(),
							sha256,
							existingAssetId.get()
					));
					continue;
				}

				try {
					assetRepository.updateSha256(candidate.assetId(), sha256);
					results.add(Sha256BackfillResult.updated(candidate.assetId(), candidate.storageKey(), sha256));
				} catch (DuplicateKeyException exception) {
					Optional<Long> concurrentExistingAssetId = assetRepository.findIdBySha256(sha256);
					if (concurrentExistingAssetId.isPresent()
							&& concurrentExistingAssetId.get().longValue() != candidate.assetId()) {
						results.add(Sha256BackfillResult.duplicate(
								candidate.assetId(),
								candidate.storageKey(),
								sha256,
								concurrentExistingAssetId.get()
						));
					} else {
						throw exception;
					}
				}
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
