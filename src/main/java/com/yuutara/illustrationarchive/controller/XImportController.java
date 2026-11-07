package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.XLikeInboxPage;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.dto.XLikeSyncReport;
import com.yuutara.illustrationarchive.service.XApiException;
import com.yuutara.illustrationarchive.service.XLikeSyncService;
import com.yuutara.illustrationarchive.service.XImportService;
import com.yuutara.illustrationarchive.service.XSyncConfirmationExpiredException;
import com.yuutara.illustrationarchive.service.XSyncReadLimitChangedException;
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
	private final XImportService importService;

	public XImportController(XLikeSyncService syncService, XImportService importService) {
		this.syncService = syncService;
		this.importService = importService;
	}

	/** {@code readLimit}: the per-sync limit the page showed. A different saved limit is refused before reading X. */
	@PostMapping("/sync")
	public XLikeSyncReport sync(@RequestParam(required = false) Integer readLimit) {
		return syncService.sync(readLimit);
	}

	@PostMapping("/sync/recent")
	public XLikeSyncSummary syncRecent(@RequestParam(required = false) Integer maxResults,
			@RequestParam(required = false) Integer maxPages) {
		return syncService.syncRecent(maxResults, maxPages);
	}

	@PostMapping("/sync/continuation")
	public XLikeSyncSummary syncContinuation(@RequestParam(required = false) Integer maxResults,
			@RequestParam(required = false) Integer maxPages) {
		return syncService.syncContinuation(maxResults, maxPages);
	}

	@PostMapping("/sync/continuation/reset")
	public ResponseEntity<Void> resetInvalidContinuation() {
		syncService.resetInvalidContinuation();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/sync/recent/reset")
	public ResponseEntity<Void> resetInvalidRecent() {
		syncService.resetInvalidRecent();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/sync/account/adopt")
	public ResponseEntity<Void> adoptProgress(@RequestBody AccountConfirmation request) {
		syncService.adoptProgress(request.confirmation());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/sync/account/restart")
	public ResponseEntity<Void> restartForRefusedAccount(@RequestBody AccountConfirmation request) {
		syncService.restartForRefusedAccount(request.confirmation());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/inbox")
	public XLikeInboxPage inbox(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return syncService.inbox(page, size);
	}

	@PatchMapping("/inbox/skip")
	public XLikeSyncService.SkipSummary skip(@RequestBody SkipRequest request) {
		return syncService.skip(request.itemIds());
	}

	@PatchMapping("/inbox/unskip")
	public XLikeSyncService.UnskipSummary unskip(@RequestBody UnskipRequest request) {
		return syncService.unskip(request.itemIds(), request.skipToken());
	}

	@GetMapping("/inbox/skipped")
	public XLikeInboxPage skipped(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return syncService.skipped(page, size);
	}

	@PostMapping("/inbox/import")
	public XImportService.Summary importSelected(@RequestBody SkipRequest request) {
		return importService.importSelected(request.itemIds());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiError> invalidPageSize(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", null, exception.getMessage()));
	}

	@ExceptionHandler(XSyncConfirmationExpiredException.class)
	public ResponseEntity<ApiError> confirmationExpired(XSyncConfirmationExpiredException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ApiError("CONFIRMATION_EXPIRED", null, exception.getMessage()));
	}

	@ExceptionHandler(XSyncReadLimitChangedException.class)
	public ResponseEntity<ReadLimitChanged> readLimitChanged(XSyncReadLimitChangedException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ReadLimitChanged("READ_LIMIT_CHANGED", exception.readLimit(), exception.getMessage()));
	}

	@ExceptionHandler(XApiException.class)
	public ResponseEntity<ApiError> xApiFailure(XApiException exception) {
		Integer upstreamStatus = exception.upstreamStatus();
		HttpStatus status = Integer.valueOf(429).equals(upstreamStatus)
				? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
		String code = switch (upstreamStatus == null ? 0 : upstreamStatus) {
			case 401 -> "X_CREDENTIAL_REJECTED";
			case 403 -> "X_ACCESS_DENIED";
			default -> "X_API_ERROR";
		};
		return ResponseEntity.status(status)
				.body(new ApiError(code, upstreamStatus, exception.getMessage()));
	}

	public record ApiError(String code, Integer upstreamStatus, String message) {
	}

	public record SkipRequest(List<Long> itemIds) {
	}

	/** {@code skipToken} from a skip's summary makes this the undo of that skip; without it, a plain put back. */
	public record UnskipRequest(List<Long> itemIds, String skipToken) {
	}

	public record ReadLimitChanged(String code, int readLimit, String message) {
	}

	public record AccountConfirmation(String confirmation) {
	}
}
