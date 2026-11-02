package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.service.GeminiKeyStore;
import com.yuutara.illustrationarchive.service.XLikeSyncBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

/** Everything the Settings page and the app shell need from the desktop launcher's side of the server. */
@RestController
@Profile(DesktopServer.PROFILE)
public class DesktopController {

	private static final Logger log = LoggerFactory.getLogger(DesktopController.class);
	/** Where the app may send the default browser; anything else stays in the app window. */
	private static final Set<String> EXTERNAL_HOSTS = Set.of("x.com", "console.x.com", "developer.x.com", "docs.x.com",
			"aistudio.google.com", "ai.google.dev");

	private final XAuthorizationService authorization;
	private final DesktopSettings settings;
	private final DesktopExternal external;
	private final DesktopActivation activation;
	private final DesktopEvidence evidence;
	private final GeminiKeyStore geminiKeys;
	private final XLikeSyncBudget budget;
	private final Path home;

	public DesktopController(XAuthorizationService authorization, DesktopSettings settings, DesktopExternal external,
			DesktopActivation activation, DesktopEvidence evidence, GeminiKeyStore geminiKeys, XLikeSyncBudget budget,
			@Value("${" + DesktopServer.HOME_PROPERTY + "}") String home) {
		this.authorization = authorization;
		this.settings = settings;
		this.external = external;
		this.activation = activation;
		this.evidence = evidence;
		this.geminiKeys = geminiKeys;
		this.budget = budget;
		this.home = Path.of(home);
	}

	/** {@code verifiedAt} and {@code verifiedAccount} come only from a finished sync on the current login, never from a guess. */
	public record XConnection(XAuthorizationService.Status status, String clientId, String callbackUrl, String scopes,
			int scanBudget, String verifiedAt, String verifiedAccount) {
	}

	public record SettingsView(String dataDirectory, String version, String proxy, boolean geminiKeySaved,
			String geminiVerifiedAt, CloseAction closeAction) {
	}

	public record ClientIdRequest(String clientId) {
	}

	public record ProxyRequest(String address) {
	}

	public record CloseActionRequest(CloseAction action) {
	}

	public record KeyRequest(String apiKey) {
	}

	public record LinkRequest(String url) {
	}

	public record DisconnectResult(boolean revoked) {
	}

	public record OpenResult(boolean opened) {
	}

	public record ErrorResponse(String message) {
	}

	/** Called by a second launch of the app, which holds the same session as the first. */
	@PostMapping("/api/desktop/show")
	public ResponseEntity<Void> show() {
		activation.show();
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/x-auth/status")
	public XConnection xStatus() {
		return connection(authorization.status());
	}

	@PostMapping("/api/x-auth/start")
	public XConnection xStart(@RequestBody ClientIdRequest request) {
		return connection(authorization.start(request.clientId() == null ? null : request.clientId().trim()));
	}

	@PostMapping("/api/x-auth/cancel")
	public XConnection xCancel() {
		authorization.cancel();
		return connection(authorization.status());
	}

	@PostMapping("/api/x-auth/disconnect")
	public DisconnectResult xDisconnect() {
		return new DisconnectResult(authorization.disconnect());
	}

	@GetMapping("/api/desktop/settings")
	public SettingsView settings() {
		// jpackage writes the release number here; the jar's manifest only has Maven's version.
		return new SettingsView(home.toString(), System.getProperty("jpackage.app-version", "开发版"), settings.proxy(),
				geminiKeys.saved(), evidence.aiVerifiedAt(), settings.closeAction());
	}

	@PutMapping("/api/desktop/settings/close-action")
	public ResponseEntity<Void> saveCloseAction(@RequestBody CloseActionRequest request) {
		if (request.action() == null) {
			throw new IllegalArgumentException("Close action is required.");
		}
		settings.saveCloseAction(request.action());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/api/desktop/settings/proxy")
	public ResponseEntity<Void> saveProxy(@RequestBody ProxyRequest request) {
		settings.saveProxy(request.address() == null ? "" : request.address().trim());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/api/desktop/settings/gemini-key")
	public ResponseEntity<Void> saveGeminiKey(@RequestBody KeyRequest request) {
		String key = request.apiKey() == null ? "" : request.apiKey().trim();
		if (key.isEmpty() || key.length() > 512 || key.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException("API key format is not valid.");
		}
		geminiKeys.save(key);
		evidence.keyChanged();
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/api/desktop/settings/gemini-key")
	public ResponseEntity<Void> clearGeminiKey() {
		geminiKeys.clear();
		evidence.keyChanged();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/desktop/open-data-folder")
	public OpenResult openDataFolder() {
		return new OpenResult(external.open(home));
	}

	@PostMapping("/api/desktop/open-external")
	public OpenResult openExternal(@RequestBody LinkRequest request) {
		URI uri = URI.create(request.url() == null ? "" : request.url());
		if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !EXTERNAL_HOSTS.contains(uri.getHost())) {
			throw new IllegalArgumentException("Only X and Google AI Studio links can be opened.");
		}
		return new OpenResult(external.browse(uri));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure) {
		return ResponseEntity.badRequest().body(new ErrorResponse(failure.getMessage()));
	}

	/** Writes to the data folder failed. The page keeps what the user typed, so saving can simply be tried again. */
	@ExceptionHandler(UncheckedIOException.class)
	public ResponseEntity<ErrorResponse> unsaved(UncheckedIOException failure) {
		log.warn("A desktop setting could not be written.", failure);
		return ResponseEntity.internalServerError().body(new ErrorResponse("写入数据文件夹失败，可能是磁盘已满或没有写入权限"));
	}

	private XConnection connection(XAuthorizationService.Status status) {
		return new XConnection(status, settings.xClientId(), authorization.callbackUrl(), XAuthorizationService.SCOPES,
				budget.maxScanned(), evidence.xVerifiedAt(), evidence.xVerifiedAccount());
	}
}
