package com.yuutara.illustrationarchive.dto;

import java.util.List;

/** Ephemeral translation; media mappings are supplied by the archive, never by the model. */
public record AiAnalysisResponse(List<Page> pages, String model,
		List<Media> analyzedAssets, List<Media> skippedAssets) {
	public record Page(int index, String description, List<PageText> texts) { }
	public record PageText(String source, String translation, String note) { }
	public record Media(long assetId, int position, String mimeType) { }
}
