package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IllustrationPersistenceService {

	private final IllustrationRepository illustrationRepository;
	private final AssetRepository assetRepository;

	public IllustrationPersistenceService(
			IllustrationRepository illustrationRepository,
			AssetRepository assetRepository
	) {
		this.illustrationRepository = illustrationRepository;
		this.assetRepository = assetRepository;
	}

	@Transactional
	public IllustrationImportResult persist(StoredFile storedFile) {
		long illustrationId = illustrationRepository.insert();
		long assetId = assetRepository.insert(
				illustrationId,
				storedFile.originalFilename(),
				storedFile.storageKey(),
				storedFile.mimeType(),
				storedFile.fileSize(),
				0
		);

		return new IllustrationImportResult(illustrationId, assetId);
	}
}
