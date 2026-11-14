package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record TrashPurgeResult(
		int requestedCount,
		int purgedCount,
		List<Long> purgedIds,
		int cleanedFiles,
		int pendingFileCleanups
) {
}
