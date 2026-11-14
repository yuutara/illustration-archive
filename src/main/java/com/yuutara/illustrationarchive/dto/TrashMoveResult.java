package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record TrashMoveResult(
		int requestedCount,
		int movedCount,
		List<Long> movedIds
) {
}
