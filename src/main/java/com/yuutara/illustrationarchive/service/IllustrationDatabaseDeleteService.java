package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class IllustrationDatabaseDeleteService {

	private final IllustrationRepository illustrationRepository;
	private final AssetRepository assetRepository;

	public IllustrationDatabaseDeleteService(
			IllustrationRepository illustrationRepository,
			AssetRepository assetRepository
	) {
		this.illustrationRepository = illustrationRepository;
		this.assetRepository = assetRepository;
	}

	@Transactional
	public List<String> delete(long illustrationId) {
		illustrationRepository.findDetailBaseById(illustrationId)
				.orElseThrow(() -> new IllustrationNotFoundException(illustrationId));

		List<String> storageKeys = assetRepository.findStorageKeysByIllustrationId(illustrationId);
		illustrationRepository.deleteById(illustrationId);

		return List.copyOf(storageKeys);
	}
}
