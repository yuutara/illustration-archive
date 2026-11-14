package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationIdsRequest;
import com.yuutara.illustrationarchive.dto.TrashMoveResult;
import com.yuutara.illustrationarchive.dto.TrashPage;
import com.yuutara.illustrationarchive.dto.TrashPurgeResult;
import com.yuutara.illustrationarchive.dto.TrashRestoreResult;
import com.yuutara.illustrationarchive.service.AssetContent;
import com.yuutara.illustrationarchive.service.AssetContentService;
import com.yuutara.illustrationarchive.service.AssetNotFoundException;
import com.yuutara.illustrationarchive.service.IllustrationTrashService;
import com.yuutara.illustrationarchive.storage.FileStorageValidationException;
import com.yuutara.illustrationarchive.storage.ThumbnailNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Desktop-only Trash management endpoints. Inactive in server mode and blocked on the phone lane by
 * {@link com.yuutara.illustrationarchive.desktop.LocalAccessFilter}.
 */
@RestController
@ConditionalOnProperty(name = "illustration-archive.ui.desktop", havingValue = "true")
public class TrashController {

	private final IllustrationTrashService trashService;
	private final AssetContentService assetContentService;

	public TrashController(IllustrationTrashService trashService, AssetContentService assetContentService) {
		this.trashService = trashService;
		this.assetContentService = assetContentService;
	}

	@PostMapping("/api/illustrations/batch/trash")
	public TrashMoveResult moveBatchToTrash(@RequestBody IllustrationIdsRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("ids must contain at least one work id.");
		}
		return trashService.moveToTrash(request.ids());
	}

	@GetMapping("/api/trash")
	public TrashPage list(
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size
	) {
		return trashService.list(page, size);
	}

	@PostMapping("/api/trash/restore")
	public TrashRestoreResult restore(@RequestBody IllustrationIdsRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("ids must contain at least one work id.");
		}
		return trashService.restoreFromTrash(request.ids());
	}

	@PostMapping("/api/trash/purge")
	public TrashPurgeResult purge(@RequestBody IllustrationIdsRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("ids must contain at least one work id.");
		}
		return trashService.purge(request.ids());
	}

	@PostMapping("/api/trash/purge-all")
	public TrashPurgeResult purgeAll() {
		return trashService.purgeAll();
	}

	@GetMapping("/api/trash/assets/{id}/content")
	public ResponseEntity<Resource> loadTrashedContent(@PathVariable long id) {
		return mediaResponse(assetContentService.loadTrashed(id));
	}

	@GetMapping("/api/trash/assets/{id}/thumbnail")
	public ResponseEntity<Resource> loadTrashedThumbnail(@PathVariable long id) {
		return mediaResponse(assetContentService.loadTrashedThumbnail(id));
	}

	private static ResponseEntity<Resource> mediaResponse(AssetContent content) {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.mimeType()))
				.contentLength(content.fileSize())
				.body(content.resource());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleInvalidRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler({
			AssetNotFoundException.class,
			ThumbnailNotFoundException.class,
			FileStorageValidationException.class
	})
	public ResponseEntity<Void> handleMissingMedia() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}
}
