package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationBatchTagCount;
import com.yuutara.illustrationarchive.dto.IllustrationBatchUpdateRequest;
import com.yuutara.illustrationarchive.dto.IllustrationIdsRequest;
import com.yuutara.illustrationarchive.service.IllustrationBatchUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/illustrations/batch")
public class IllustrationBatchController {

	private final IllustrationBatchUpdateService batchUpdateService;

	public IllustrationBatchController(IllustrationBatchUpdateService batchUpdateService) {
		this.batchUpdateService = batchUpdateService;
	}

	@PostMapping("/metadata")
	public ResponseEntity<Void> updateMetadata(@RequestBody IllustrationBatchUpdateRequest request) {
		batchUpdateService.update(request);
		return ResponseEntity.noContent().build();
	}

	/** A POST only because the id list can be too long for a URL; it changes nothing. */
	@PostMapping("/tags")
	public List<IllustrationBatchTagCount> countTags(@RequestBody IllustrationIdsRequest request) {
		return batchUpdateService.countTags(request.ids());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
	}
}
