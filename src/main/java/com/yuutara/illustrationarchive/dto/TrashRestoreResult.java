package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record TrashRestoreResult(
		int requestedCount,
		int restoredCount,
		List<Long> restoredIds
) {
}
