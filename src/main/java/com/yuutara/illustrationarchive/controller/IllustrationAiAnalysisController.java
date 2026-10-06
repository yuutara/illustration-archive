package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AiAnalysisResponse;
import com.yuutara.illustrationarchive.service.IllustrationAiAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IllustrationAiAnalysisController {
	private final IllustrationAiAnalysisService analysis;

	public IllustrationAiAnalysisController(IllustrationAiAnalysisService analysis) {
		this.analysis = analysis;
	}

	@PostMapping("/api/illustrations/{id}/ai-analysis")
	public ResponseEntity<AiAnalysisResponse> analyze(@PathVariable long id) {
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(analysis.analyze(id));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> failure(ResponseStatusException exception) {
		return ResponseEntity.status(exception.getStatusCode()).header("Cache-Control", "no-store")
				.body(new ErrorResponse(exception.getReason()));
	}

	public record ErrorResponse(String message) { }
}
