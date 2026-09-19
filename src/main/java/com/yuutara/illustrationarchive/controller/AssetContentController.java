package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.AssetContent;
import com.yuutara.illustrationarchive.service.AssetContentService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
public class AssetContentController {

	private final AssetContentService assetContentService;

	public AssetContentController(AssetContentService assetContentService) {
		this.assetContentService = assetContentService;
	}

	@GetMapping("/{id}/content")
	public ResponseEntity<Resource> getContent(@PathVariable long id) {
		AssetContent content = assetContentService.load(id);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.mimeType()))
				.contentLength(content.fileSize())
				.body(content.resource());
	}
}
