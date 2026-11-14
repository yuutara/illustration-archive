package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AssetContentInfo;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.storage.FileStorage;
import com.yuutara.illustrationarchive.storage.FileStorageException;
import com.yuutara.illustrationarchive.storage.ThumbnailNotFoundException;
import com.yuutara.illustrationarchive.storage.ThumbnailStorage;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class AssetContentService {

	private final AssetRepository assetRepository;
	private final FileStorage fileStorageService;
	private final ThumbnailStorage thumbnailService;

	public AssetContentService(AssetRepository assetRepository, FileStorage fileStorageService,
			ThumbnailStorage thumbnailService) {
		this.assetRepository = assetRepository;
		this.fileStorageService = fileStorageService;
		this.thumbnailService = thumbnailService;
	}

	public AssetContent load(long assetId) {
		return loadContent(assetRepository.findContentInfoById(assetId), assetId);
	}

	public AssetContent loadThumbnail(long assetId) {
		return loadThumb(assetRepository.findContentInfoById(assetId), assetId);
	}

	public AssetContent loadTrashed(long assetId) {
		return loadContent(assetRepository.findTrashedContentInfoById(assetId), assetId);
	}

	public AssetContent loadTrashedThumbnail(long assetId) {
		return loadThumb(assetRepository.findTrashedContentInfoById(assetId), assetId);
	}

	private AssetContent loadContent(java.util.Optional<AssetContentInfo> found, long assetId) {
		AssetContentInfo contentInfo = found.orElseThrow(() -> new AssetNotFoundException(assetId));
		Resource resource = fileStorageService.load(contentInfo.storageKey());

		return new AssetContent(resource, contentInfo.mimeType(), contentInfo.fileSize());
	}

	private AssetContent loadThumb(java.util.Optional<AssetContentInfo> found, long assetId) {
		AssetContentInfo contentInfo = found.orElseThrow(() -> new AssetNotFoundException(assetId));
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
