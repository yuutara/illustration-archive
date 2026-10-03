package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import com.yuutara.illustrationarchive.service.IllustrationGalleryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationGalleryController {

	private final IllustrationGalleryService illustrationGalleryService;

	public IllustrationGalleryController(IllustrationGalleryService illustrationGalleryService) {
		this.illustrationGalleryService = illustrationGalleryService;
	}

	@GetMapping
	public IllustrationGalleryPage getGallery(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "24") int size,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) List<Long> authorId,
			@RequestParam(required = false) List<Long> tagId
	) {
		return illustrationGalleryService.getGallery(page, size, IllustrationGalleryQuery.withIds(q, authorId, tagId));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> invalidQuery(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
	}
}
