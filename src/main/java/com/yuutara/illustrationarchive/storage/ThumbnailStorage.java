package com.yuutara.illustrationarchive.storage;

import org.springframework.core.io.Resource;

/** Thumbnail operations currently needed by import, HTTP content, delete, and backfill. */
public interface ThumbnailStorage {
	String generateThumbnail(String storageKey);

	Resource loadThumbnail(String storageKey);

	void deleteThumbnail(String storageKey);
}
