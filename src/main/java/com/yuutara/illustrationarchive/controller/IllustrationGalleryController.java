package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.service.IllustrationGalleryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
			@RequestParam(defaultValue = "24") int size
	) {
		return illustrationGalleryService.getGallery(page, size);
	}
}
