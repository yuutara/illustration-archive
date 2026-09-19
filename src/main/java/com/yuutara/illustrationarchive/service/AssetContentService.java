package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class AssetContentService {

	private final AssetRepository assetRepository;
	private final FileStorageService fileStorageService;

	public AssetContentService(AssetRepository assetRepository, FileStorageService fileStorageService) {
		this.assetRepository = assetRepository;
		this.fileStorageService = fileStorageService;
	}

	public AssetContent load(long assetId) {
		AssetContentInfo contentInfo = assetRepository.findContentInfoById(assetId)
				.orElseThrow(() -> new AssetNotFoundException(assetId));
		Resource resource = fileStorageService.load(contentInfo.storageKey());

		return new AssetContent(resource, contentInfo.mimeType(), contentInfo.fileSize());
	}
}
