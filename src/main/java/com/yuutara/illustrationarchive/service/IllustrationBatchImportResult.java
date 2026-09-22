package com.yuutara.illustrationarchive.service;

import java.util.List;

public record IllustrationBatchImportResult(
		int total,
		int successCount,
		int duplicateCount,
		int failureCount,
		List<IllustrationBatchImportItemResult> items
) {

	public IllustrationBatchImportResult(
			int total,
			int successCount,
			int failureCount,
			List<IllustrationBatchImportItemResult> items
	) {
		this(total, successCount, countDuplicates(items), countFailures(failureCount, items), items);
	}

	private static int countDuplicates(List<IllustrationBatchImportItemResult> items) {
		return (int) items.stream()
				.filter(item -> IllustrationBatchImportItemResult.DUPLICATE.equals(item.status()))
				.count();
	}

	private static int countFailures(int previousFailureCount, List<IllustrationBatchImportItemResult> items) {
		return Math.max(0, previousFailureCount - countDuplicates(items));
	}
}
