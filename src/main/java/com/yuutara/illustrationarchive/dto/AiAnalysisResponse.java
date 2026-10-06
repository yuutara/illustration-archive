package com.yuutara.illustrationarchive.dto;

import java.util.List;

/** Ephemeral analysis; media mappings are supplied by the archive, never by the model. */
public record AiAnalysisResponse(String summary, List<Page> pages, List<CharacterCandidate> characters,
		List<String> relationships, List<String> suggestedTags, String model,
		List<Media> analyzedAssets, List<Media> skippedAssets) {
	public record Page(int index, String description, List<PageText> texts) { }
	public record PageText(String source, String translation, String note) { }
	public record CharacterCandidate(String name, String description) { }
	public record Media(long assetId, int position, String mimeType) { }
}
