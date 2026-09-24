package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.ThumbnailNotFoundException;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class AssetContentService {

	private final AssetRepository assetRepository;
	private final FileStorageService fileStorageService;
	private final ThumbnailService thumbnailService;

	public AssetContentService(AssetRepository assetRepository, FileStorageService fileStorageService,
			ThumbnailService thumbnailService) {
		this.assetRepository = assetRepository;
		this.fileStorageService = fileStorageService;
		this.thumbnailService = thumbnailService;
	}

	public AssetContent load(long assetId) {
		AssetContentInfo contentInfo = assetRepository.findContentInfoById(assetId)
				.orElseThrow(() -> new AssetNotFoundException(assetId));
		Resource resource = fileStorageService.load(contentInfo.storageKey());

		return new AssetContent(resource, contentInfo.mimeType(), contentInfo.fileSize());
	}

	public AssetContent loadThumbnail(long assetId) {
		AssetContentInfo contentInfo = assetRepository.findContentInfoById(assetId)
				.orElseThrow(() -> new AssetNotFoundException(assetId));
		if (!contentInfo.mimeType().equals("image/jpeg") && !contentInfo.mimeType().equals("image/png")) {
			throw new ThumbnailNotFoundException();
		}

		Resource resource = thumbnailService.loadThumbnail(contentInfo.storageKey());
		try {
			return new AssetContent(resource, contentInfo.mimeType(), resource.contentLength());
		} catch (IOException exception) {
			throw new FileStorageException("Failed to read thumbnail file size.", exception);
		}
	}
}
