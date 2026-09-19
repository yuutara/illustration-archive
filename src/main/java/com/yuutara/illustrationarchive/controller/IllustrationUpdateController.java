package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.service.IllustrationUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationUpdateController {

	private final IllustrationUpdateService illustrationUpdateService;

	public IllustrationUpdateController(IllustrationUpdateService illustrationUpdateService) {
		this.illustrationUpdateService = illustrationUpdateService;
	}

	@PatchMapping("/{id}")
	public ResponseEntity<Void> updateBasicMetadata(
			@PathVariable long id,
			@RequestBody IllustrationPatchRequest request
	) {
		illustrationUpdateService.updateBasicMetadata(id, request);
		return ResponseEntity.noContent().build();
	}
}
