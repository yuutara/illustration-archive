package com.yuutara.illustrationarchive.dto;

import java.util.List;

public record XLikePage(List<XLikeCandidate> candidates, boolean hasMore) {
}
