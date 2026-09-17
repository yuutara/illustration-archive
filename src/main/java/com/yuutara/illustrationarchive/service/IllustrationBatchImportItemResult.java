package com.yuutara.illustrationarchive.service;

public record IllustrationBatchImportItemResult(
		String filename,
		boolean success,
		Long illustrationId,
		String errorCode,
		String message
) {
}
