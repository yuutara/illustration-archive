package com.yuutara.illustrationarchive.service;

import java.util.List;

public record IllustrationBatchImportResult(
		int total,
		int successCount,
		int failureCount,
		List<IllustrationBatchImportItemResult> items
) {
}
