package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.service.XApiException;
import com.yuutara.illustrationarchive.service.XLikeSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/x-import")
public class XImportController {
	private final XLikeSyncService syncService;

	public XImportController(XLikeSyncService syncService) {
		this.syncService = syncService;
	}

	@PostMapping("/sync/recent")
	public XLikeSyncSummary syncRecent(@RequestParam(required = false) Integer maxResults) {
		return syncService.syncRecent(maxResults);
	}

	@GetMapping("/inbox")
	public List<XLikeInboxItem> inbox() {
		return syncService.inbox();
	}

	@PatchMapping("/inbox/skip")
	public XLikeSyncService.SkipSummary skip(@RequestBody SkipRequest request) {
		return syncService.skip(request.itemIds());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiError> invalidPageSize(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", null, exception.getMessage()));
	}

	@ExceptionHandler(XApiException.class)
	public ResponseEntity<ApiError> xApiFailure(XApiException exception) {
		HttpStatus status = exception.upstreamStatus() != null && exception.upstreamStatus() == 429
				? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
		return ResponseEntity.status(status)
				.body(new ApiError("X_API_ERROR", exception.upstreamStatus(), exception.getMessage()));
	}

	public record ApiError(String code, Integer upstreamStatus, String message) {
	}

	public record SkipRequest(List<Long> itemIds) {
	}
}
