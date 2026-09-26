package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.storage.FileStorageService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import com.yuutara.illustrationarchive.storage.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class XImportService {
	private static final Logger log = LoggerFactory.getLogger(XImportService.class);
	private final XLikeRepository items;
	private final XLikeMediaRepository mediaRepository;
	private final XPhotoDownloadService downloader;
	private final XPostPersistenceService persistence;
	private final AssetRepository assets;
	private final FileStorageService storage;
	private final ThumbnailService thumbnails;

	public XImportService(XLikeRepository items, XLikeMediaRepository mediaRepository,
			XPhotoDownloadService downloader, XPostPersistenceService persistence,
			AssetRepository assets, FileStorageService storage, ThumbnailService thumbnails) {
		this.items = items;
		this.mediaRepository = mediaRepository;
		this.downloader = downloader;
		this.persistence = persistence;
		this.assets = assets;
		this.storage = storage;
		this.thumbnails = thumbnails;
	}

	public Summary importSelected(List<Long> itemIds) {
		if (itemIds == null || itemIds.isEmpty() || itemIds.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new IllegalArgumentException("itemIds must contain at least one positive item id.");
		}
		List<ItemResult> results = new ArrayList<>();
		for (long itemId : itemIds.stream().distinct().toList()) {
			results.add(importOne(itemId));
		}
		return new Summary(results.size(),
				(int) results.stream().filter(result -> result.status().equals("SUCCESS")).count(),
				(int) results.stream().filter(result -> result.status().equals("DUPLICATE")).count(),
				(int) results.stream().filter(result -> result.status().equals("FAILED")).count(),
				List.copyOf(results));
	}

	private ItemResult importOne(long itemId) {
		List<StoredFile> files = new ArrayList<>();
		try {
			XLikeRepository.ImportItem item = items.findForImport(itemId, false)
					.orElseThrow(() -> new IllegalArgumentException("X Like item does not exist."));
			if (item.status() != XLikeStatus.PENDING) {
				throw new IllegalStateException("Only PENDING X Like items can be imported.");
			}
			List<XLikeMedia> media = mediaRepository.findByItemId(itemId);
			if (media.isEmpty()) throw new IllegalStateException("X Like item has no photos.");
			for (XLikeMedia photo : media) files.add(downloader.download(photo));
			long illustrationId = persistence.persist(item, media, files);
			for (StoredFile file : files) {
				if (!"image/jpeg".equals(file.mimeType()) && !"image/png".equals(file.mimeType())) continue;
				try {
					thumbnails.generateThumbnail(file.storageKey());
				} catch (RuntimeException failure) {
					log.warn("X Post thumbnail generation failed. illustrationId={}, storageKey={}",
							illustrationId, file.storageKey(), failure);
				}
			}
			return new ItemResult(itemId, "SUCCESS", illustrationId, null);
		} catch (RuntimeException original) {
			RuntimeException failure = original;
			if (original instanceof DuplicateKeyException) {
				try {
					if (files.stream().anyMatch(file -> assets.findIllustrationIdBySha256(file.sha256()).isPresent())) {
						failure = new XPostDuplicateException("An X Post photo already exists in the archive.");
						failure.addSuppressed(original);
					}
				} catch (RuntimeException lookupFailure) {
					original.addSuppressed(lookupFailure);
				}
			}
			for (StoredFile file : files) {
				try {
					storage.delete(file.storageKey());
				} catch (RuntimeException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
					log.error("Failed to clean up X Post photo. storageKey={}", file.storageKey(), cleanupFailure);
				}
			}
			if (failure instanceof XPostDuplicateException) {
				return new ItemResult(itemId, "DUPLICATE", null, failure.getMessage());
			}
			log.warn("X Post import failed. itemId={}", itemId, failure);
			return new ItemResult(itemId, "FAILED", null, failure.getMessage());
		}
	}

	public record ItemResult(long itemId, String status, Long illustrationId, String reason) {
	}
	public record Summary(int total, int successCount, int duplicateCount, int failureCount,
			List<ItemResult> items) {
	}
}
