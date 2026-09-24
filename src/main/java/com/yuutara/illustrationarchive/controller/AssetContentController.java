package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.AssetContent;
import com.yuutara.illustrationarchive.service.AssetContentService;
import com.yuutara.illustrationarchive.service.AssetNotFoundException;
import com.yuutara.illustrationarchive.storage.ThumbnailNotFoundException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
		return response(content);
	}

	@GetMapping("/{id}/thumbnail")
	public ResponseEntity<Resource> getThumbnail(@PathVariable long id) {
		AssetContent content = assetContentService.loadThumbnail(id);
		return response(content);
	}

	@ExceptionHandler({AssetNotFoundException.class, ThumbnailNotFoundException.class})
	public ResponseEntity<Void> handleMissingAssetOrThumbnail() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

	private ResponseEntity<Resource> response(AssetContent content) {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.mimeType()))
				.contentLength(content.fileSize())
				.body(content.resource());
	}
}
