package com.yuutara.illustrationarchive.service;

import org.springframework.core.io.Resource;

/**
 * Internal result that combines a stored file resource with its response metadata.
 */
public record AssetContent(
		Resource resource,
		String mimeType,
		long fileSize
) {
}
