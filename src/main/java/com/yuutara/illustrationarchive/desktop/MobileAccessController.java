package com.yuutara.illustrationarchive.desktop;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/** Settings' phone access section, plus the one page a scanned QR lands on. */
@RestController
@Profile(DesktopServer.PROFILE)
public class MobileAccessController {

	private static final Logger log = LoggerFactory.getLogger(MobileAccessController.class);
	private static final Duration DEVICE_LIFETIME = Duration.ofDays(180);

	private final MobileAccess mobile;

	public MobileAccessController(MobileAccess mobile) {
		this.mobile = mobile;
	}

	public record AccessRequest(Boolean enabled) {
	}

	public record PairingRequest(String address) {
	}

	public record ErrorResponse(String message) {
	}

	@GetMapping("/api/desktop/mobile")
	public MobileAccess.Status status() {
		return mobile.status();
	}

	@PutMapping("/api/desktop/mobile/access")
	public MobileAccess.Status access(@RequestBody AccessRequest request) {
		if (request.enabled() == null) {
			throw new IllegalArgumentException("请说明是打开还是关闭手机访问。");
		}
		return mobile.setEnabled(request.enabled());
	}

	@PostMapping("/api/desktop/mobile/pairing")
	public MobileAccess.PairingView pairing(@RequestBody(required = false) PairingRequest request) {
		return mobile.startPairing(request == null ? null : request.address());
	}

	@DeleteMapping("/api/desktop/mobile/devices/{id}")
	public ResponseEntity<Void> revoke(@PathVariable String id) {
		return mobile.revoke(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	/**
	 * The QR URL. A valid code becomes an HttpOnly device cookie once; the phone never receives a password or a URL
	 * that keeps working after the code is used.
	 */
	@GetMapping("/m/pair")
	public void pair(@RequestParam(required = false) String code,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
			HttpServletResponse response) throws IOException {
		String token = mobile.pair(code, userAgent);
		if (token == null) {
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.sendRedirect("/m/pair.html?e=1");
			return;
		}
		ResponseCookie cookie = ResponseCookie.from(MobileAccess.DEVICE_COOKIE, token)
				.httpOnly(true).sameSite("Lax").path("/").maxAge(DEVICE_LIFETIME).build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.sendRedirect("/m/");
	}

	@ExceptionHandler(MobileAccess.MobileAccessException.class)
	public ResponseEntity<ErrorResponse> unavailable(MobileAccess.MobileAccessException failure) {
		return ResponseEntity.status(409).body(new ErrorResponse(failure.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure) {
		return ResponseEntity.badRequest().body(new ErrorResponse(failure.getMessage()));
	}

	/** Writes to the data folder failed. The page keeps the switch where it was, so it can simply be tried again. */
	@ExceptionHandler(UncheckedIOException.class)
	public ResponseEntity<ErrorResponse> unsaved(UncheckedIOException failure) {
		log.warn("The phone access setting could not be written.", failure);
		return ResponseEntity.internalServerError().body(new ErrorResponse("写入数据文件夹失败，可能是磁盘已满或没有写入权限"));
	}
}
