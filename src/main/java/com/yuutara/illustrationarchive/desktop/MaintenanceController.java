package com.yuutara.illustrationarchive.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backup, restore and import from the old app, from Settings. Files are chosen in native dialogs on the launcher's
 * side, so the page never names a path; a restore or import is confirmed by the id the check returned, which stands
 * for exactly what was checked. {@code GET /api/desktop/restore} follows whichever of the two is being checked or
 * waits, and its cancel serves both. The launcher connects its {@link LibraryMaintenance} each time it starts the
 * server.
 */
@RestController
@Profile(DesktopServer.PROFILE)
public class MaintenanceController {

	private final XAuthorizationService authorization;
	private volatile LibraryMaintenance maintenance;

	public MaintenanceController(XAuthorizationService authorization) {
		this.authorization = authorization;
	}

	public record Started(boolean started) {
	}

	public record OperationRequest(String id) {
	}

	public record ErrorResponse(String message) {
	}

	void connect(LibraryMaintenance maintenance) {
		this.maintenance = maintenance;
	}

	@PostMapping("/api/desktop/backup")
	public Started backup() throws MaintenanceException {
		requireNoLoginInProgress();
		return new Started(maintenance().backup());
	}

	@GetMapping("/api/desktop/restore")
	public LibraryMaintenance.RestoreView restore() throws MaintenanceException {
		return maintenance().restoreView();
	}

	@PostMapping("/api/desktop/restore/check")
	public LibraryMaintenance.RestoreView check() throws MaintenanceException {
		return maintenance().check();
	}

	@PostMapping("/api/desktop/restore/cancel")
	public LibraryMaintenance.RestoreView cancel(@RequestBody OperationRequest request) throws MaintenanceException {
		maintenance().cancelRestore(request.id());
		return maintenance().restoreView();
	}

	@PostMapping("/api/desktop/restore/apply")
	public ResponseEntity<Void> apply(@RequestBody OperationRequest request) throws MaintenanceException {
		requireNoLoginInProgress();
		maintenance().restore(request.id());
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/api/desktop/import/check")
	public LibraryMaintenance.RestoreView checkImport() throws MaintenanceException {
		return maintenance().checkImport();
	}

	@PostMapping("/api/desktop/import/apply")
	public ResponseEntity<Void> applyImport(@RequestBody OperationRequest request) throws MaintenanceException {
		requireNoLoginInProgress();
		maintenance().apply(LibraryMaintenance.Kind.IMPORT, request.id());
		return ResponseEntity.accepted().build();
	}

	/** Stopping the server would end an X login halfway, after the user may already have approved it on X. */
	private void requireNoLoginInProgress() throws MaintenanceException {
		if (authorization.status().state() == XAuthorizationService.State.WAITING) {
			throw new MaintenanceException("X 授权正在进行，请先完成或取消后再试。");
		}
	}

	private LibraryMaintenance maintenance() throws MaintenanceException {
		LibraryMaintenance connected = maintenance;
		if (connected == null) {
			throw new MaintenanceException("备份、恢复和导入只能在桌面应用里使用。");
		}
		return connected;
	}

	@ExceptionHandler(MaintenanceException.class)
	public ResponseEntity<ErrorResponse> refused(MaintenanceException refused) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(refused.getMessage()));
	}
}
