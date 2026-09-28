package com.yuutara.illustrationarchive.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/** Capabilities used by archive services for original media, addressed by storage key. */
public interface FileStorage {
	StoredFile store(String originalFilename, InputStream source);

	Resource load(String storageKey);

	void delete(String storageKey);

	String calculateSha256(String storageKey);
}
