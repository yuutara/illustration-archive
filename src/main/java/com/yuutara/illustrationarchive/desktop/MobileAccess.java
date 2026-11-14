package com.yuutara.illustrationarchive.desktop;

import jakarta.annotation.PreDestroy;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Optional LAN access for phones. The desktop server always stays on 127.0.0.1; turning this on adds a second Tomcat
 * connector bound to the one local IPv4 address the user authorized, and turning it off removes that connector, so the
 * port really is unreachable again. Requests on the LAN connector are allowed only with a device token created by
 * scanning a one-time pairing code.
 *
 * <p>The connector is owned by the running Tomcat, not by a separate proxy: settings, cookies, images and the mobile
 * pages therefore behave exactly like the rest of the app.
 *
 * <p>A failed "close" must not silently reopen on the next launch. The current run always stops listening; if the
 * settings file cannot be written, {@code <home>\mobile-access.off} records the close intent, and startup honours that
 * marker even while the old settings file still says enabled.
 */
@Component
@Profile(DesktopServer.PROFILE)
public class MobileAccess {

	public static final String LAN_PORT_PROPERTY = "illustration-archive.desktop.mobile-port";
	public static final String DEVICE_COOKIE = "ia_mobile_device";
	static final Duration PAIRING_LIFETIME = Duration.ofMinutes(5);
	static final String OFF_MARKER = "mobile-access.off";
	static final String X_INBOX_OFF_MARKER = "mobile-x-inbox.off";

	private static final Logger log = LoggerFactory.getLogger(MobileAccess.class);
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int CODE_LENGTH = 8;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final DesktopSettings settings;
	private final ApplicationContext spring;
	private final MobileDeviceStore devices;
	private final int preferredPort;
	private final Path offMarker;
	private final Path xInboxOffMarker;

	private final Object lock = new Object();
	private volatile Connector connector;
	private volatile String boundAddress;
	private volatile String selectedAddress;
	private volatile boolean desiredEnabled;
	private volatile boolean xInboxEnabled;
	private volatile Pairing pairing;
	private volatile String lastError;

	public record DeviceView(String id, String label, String pairedAt) {
	}

	public record PairingView(String code, String url, String qr, String address, String expiresAt) {
	}

	/** {@code enabled} is the current intent; {@code active} is whether the selected address is listening right now. */
	public record Status(boolean enabled, boolean active, int port, List<String> addresses, String address,
			String error, List<DeviceView> devices, PairingView pairing, boolean xInboxEnabled) {
	}

	private record Pairing(String code, String url, String qr, String address, Instant expiresAt) {
	}

	public static class MobileAccessException extends RuntimeException {
		public MobileAccessException(String message) {
			super(message);
		}

		public MobileAccessException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	MobileAccess(DesktopSettings settings, ApplicationContext spring, MobileDeviceStore devices,
			@Value("${" + DesktopServer.HOME_PROPERTY + "}") String home,
			@Value("${" + LAN_PORT_PROPERTY + ":47393}") int preferredPort) {
		this.settings = settings;
		this.spring = spring;
		this.devices = devices;
		this.preferredPort = preferredPort < 0 || preferredPort > 65535 ? 47393 : preferredPort;
		Path homeDir = Path.of(home).toAbsolutePath().normalize();
		this.offMarker = homeDir.resolve(OFF_MARKER);
		this.xInboxOffMarker = homeDir.resolve(X_INBOX_OFF_MARKER);
	}

	@EventListener(ApplicationReadyEvent.class)
	void onReady() {
		synchronized (lock) {
			if (Files.exists(xInboxOffMarker)) {
				xInboxEnabled = false;
				recoverFromFailedXInboxClose();
			} else {
				xInboxEnabled = settings.mobileXInboxEnabled();
			}
			if (Files.exists(offMarker)) {
				desiredEnabled = false;
				recoverFromFailedClose();
				return;
			}
			desiredEnabled = settings.mobileEnabled();
			if (!desiredEnabled) {
				return;
			}
			restoreSavedConnector();
		}
	}

	/**
	 * A restart may only restore the address the user actually authorized. Never pick the first adapter when that
	 * address is missing: it might be the very interface an earlier paired phone used.
	 */
	private void restoreSavedConnector() {
		String saved = settings.mobileAddress();
		if (saved.isEmpty()) {
			lastError = "手机访问上次是打开的，但还没有记住要监听的局域网地址。请重新选择地址后再打开。";
			return;
		}
		selectedAddress = saved;
		if (!LanAddresses.siteLocal().contains(saved)) {
			lastError = "上次使用的局域网地址 " + saved + " 已不在线，手机访问保持关闭。请重新选择地址。";
			return;
		}
		try {
			startConnector(saved, preferredPort == 0 ? 0 : preferredPort);
			lastError = null;
		} catch (RuntimeException failed) {
			lastError = message(failed);
			log.warn("手机访问没能打开，应用继续只在本机提供图库。", failed);
		}
	}

	/**
	 * The last close could not be written to settings. Stay off; when the settings file is writable again, record the
	 * close there and remove the marker. If that write still fails, keep both the marker and a visible error.
	 */
	private void recoverFromFailedClose() {
		if (!settings.mobileEnabled()) {
			clearMarker(offMarker, "手机关闭标记");
			return;
		}
		try {
			settings.saveMobileEnabled(false);
			clearMarker(offMarker, "手机关闭标记");
			lastError = null;
		} catch (RuntimeException failed) {
			lastError = "上次关闭没能写入设置，本次保持关闭。修复存储后再试一次关闭。";
			log.warn("手机访问保持关闭：上次的关闭状态仍没能写入设置。", failed);
		}
	}

	private void recoverFromFailedXInboxClose() {
		if (!settings.mobileXInboxEnabled()) {
			clearMarker(xInboxOffMarker, "手机 X 收藏关闭标记");
			return;
		}
		try {
			settings.saveMobileXInboxEnabled(false);
			clearMarker(xInboxOffMarker, "手机 X 收藏关闭标记");
		} catch (RuntimeException failed) {
			if (lastError == null || lastError.isBlank()) {
				lastError = "上次关闭手机整理 X 收藏权限没能写入设置，本次保持关闭。修复存储后再试一次关闭。";
			}
			log.warn("手机 X 收藏权限保持关闭：上次的关闭状态仍没能写入设置。", failed);
		}
	}

	@PreDestroy
	void close() {
		synchronized (lock) {
			stopConnector();
		}
	}

	/** The bound LAN port while the connector is really listening, or -1. */
	public int port() {
		Connector current = connector;
		return usable(current) ? current.getLocalPort() : -1;
	}

	/** The address the connector is bound to, or null when it is not listening. */
	public String boundAddress() {
		return port() > 0 ? boundAddress : null;
	}

	public boolean acceptsDevice(String token) {
		return devices.accepts(token);
	}

	public boolean isXInboxEnabled() {
		return xInboxEnabled;
	}

	public synchronized Status status() {
		return statusForAddresses(LanAddresses.siteLocal());
	}

	/** Package-private seam so tests can exercise "the authorized address disappeared" without touching real adapters. */
	synchronized Status statusForAddresses(List<String> addresses) {
		reconcile(addresses);
		Connector current = connector;
		boolean active = usable(current);
		String address = active ? boundAddress : availableAddress(addresses, selectedAddress);
		Pairing activePairing = pairing;
		PairingView view = activePairing == null || !activePairing.expiresAt().isAfter(Instant.now())
				? null
				: new PairingView(activePairing.code(), activePairing.url(), activePairing.qr(), activePairing.address(),
						activePairing.expiresAt().toString());
		return new Status(desiredEnabled, active, active ? current.getLocalPort() : -1, addresses, address, lastError,
				devices.devices().stream()
						.map(device -> new DeviceView(device.id(), device.label(), device.pairedAt().toString()))
						.toList(), view, xInboxEnabled);
	}

	/**
	 * Stops listening when the selected address disappeared or the connector did not actually start. The next status
	 * reports that clearly; the user retries after reconnecting or picks another address.
	 */
	private void reconcile(List<String> addresses) {
		Connector current = connector;
		if (current == null) {
			return;
		}
		if (usable(current) && boundAddress != null && addresses.contains(boundAddress)) {
			return;
		}
		String keptError = lastError;
		stopConnector();
		if (desiredEnabled) {
			lastError = keptError == null || keptError.isBlank()
					? "当前选择的局域网地址已不在线，手机访问已停止。请重新选择地址或重试。"
					: keptError;
		}
	}

	public synchronized Status setEnabled(boolean enabled) {
		return setEnabled(enabled, null);
	}

	/** {@code requestedAddress} lets the retry button use the address currently picked in Settings. */
	public synchronized Status setEnabled(boolean enabled, String requestedAddress) {
		if (enabled) {
			List<String> addresses = LanAddresses.siteLocal();
			String saved = settings.mobileAddress();
			String requested = requestedAddress == null ? "" : requestedAddress.trim();
			String wanted = requested.isEmpty() ? saved : requested;
			// An explicit or remembered offline address is not permission to use whichever adapter is first.
			requireAvailable(addresses, wanted);
			if (usable(connector)) {
				if (boundAddress != null && !boundAddress.equals(saved)) {
					settings.saveMobileAddress(boundAddress);
				}
				settings.saveMobileEnabled(true);
				desiredEnabled = true;
				clearMarker(offMarker, "手机关闭标记");
				lastError = null;
				return status();
			}

			String chosen = wanted.isEmpty() ? (addresses.isEmpty() ? null : addresses.get(0)) : wanted;
			if (chosen == null) {
				throw new MobileAccessException("没有找到可用的局域网地址，请确认电脑和手机连在同一个 Wi-Fi。");
			}
			// Record the address before listening: a restart must never fall back to a different adapter.
			if (!chosen.equals(saved)) {
				settings.saveMobileAddress(chosen);
			}
			settings.saveMobileEnabled(true);
			desiredEnabled = true;
			clearMarker(offMarker, "手机关闭标记");
			try {
				startConnector(chosen, preferredPort == 0 ? 0 : preferredPort);
				lastError = null;
			} catch (RuntimeException failed) {
				lastError = message(failed);
				stopConnector();
				desiredEnabled = false;
				try {
					settings.saveMobileEnabled(false);
				} catch (RuntimeException alsoFailed) {
					// Keep the original failure; the next status reports that the switch could not be written back.
					log.warn("手机访问没能打开，而且没能把开关写回关闭。", alsoFailed);
				}
				throw new MobileAccessException(lastError, failed);
			}
			return status();
		}

		// Stop first: "关闭" means the port is gone, not merely that the page hides a button.
		stopConnector();
		desiredEnabled = false;
		boolean markerWritten = writeMarker(offMarker, "手机关闭标记");
		try {
			settings.saveMobileEnabled(false);
			clearMarker(offMarker, "手机关闭标记");
			lastError = null;
		} catch (RuntimeException failed) {
			lastError = markerWritten
					? "手机访问已关闭，但开关没能写入设置；下次启动会保持关闭。可以再试一次保存。"
					: "手机访问已关闭，但关闭状态没能保存；下次启动可能仍会自动打开。请修复存储后重试。";
			log.warn("手机访问已关闭，但设置没有保存。", failed);
		}
		return status();
	}

	public synchronized Status setXInboxEnabled(boolean enabled) {
		if (enabled) {
			settings.saveMobileXInboxEnabled(true);
			xInboxEnabled = true;
			clearMarker(xInboxOffMarker, "手机 X 收藏关闭标记");
			lastError = null;
			return status();
		}
		xInboxEnabled = false;
		boolean markerWritten = writeMarker(xInboxOffMarker, "手机 X 收藏关闭标记");
		try {
			settings.saveMobileXInboxEnabled(false);
			clearMarker(xInboxOffMarker, "手机 X 收藏关闭标记");
			lastError = null;
		} catch (RuntimeException failed) {
			lastError = markerWritten
					? "手机整理 X 收藏权限已关闭，但设置未能写入磁盘；下次启动会保持关闭。可再试一次保存。"
					: "手机整理 X 收藏权限已关闭，但关闭状态没能保存；下次启动可能仍会自动打开。请修复存储后重试。";
			log.warn("手机 X 收藏权限已关闭，但设置没有保存。", failed);
		}
		return status();
	}

	public synchronized PairingView startPairing(String address) {
		if (!desiredEnabled) {
			throw new MobileAccessException("手机访问还没有打开。");
		}
		List<String> addresses = LanAddresses.siteLocal();
		String requested = address == null ? "" : address.trim();
		String wanted = requested.isEmpty()
				? (boundAddress != null ? boundAddress : settings.mobileAddress())
				: requested;
		if (wanted.isEmpty()) {
			throw new MobileAccessException("还没有选择局域网地址，请先选择一个地址。");
		}
		requireAvailable(addresses, wanted);
		String chosen = wanted;
		if (!usable(connector) || !chosen.equals(boundAddress)) {
			rebind(chosen);
		} else if (!chosen.equals(settings.mobileAddress())) {
			settings.saveMobileAddress(chosen);
		}
		Connector current = connector;
		if (!usable(current)) {
			throw new MobileAccessException("手机访问的连接没有启动，请重试。");
		}
		String code = newCode();
		String url = "http://" + chosen + ":" + current.getLocalPort() + "/m/pair?code=" + code;
		Pairing created = new Pairing(code, url, MobileQr.svgDataUri(url), chosen, Instant.now().plus(PAIRING_LIFETIME));
		pairing = created;
		selectedAddress = chosen;
		return new PairingView(created.code(), created.url(), created.qr(), created.address(), created.expiresAt().toString());
	}

	/** A different address is authorized only after it is durable and the connector has really moved there. */
	private void rebind(String address) {
		Connector previous = connector;
		String previousAddress = boundAddress;
		int previousPort = previous == null ? 0 : previous.getLocalPort();
		String previousSaved = settings.mobileAddress();
		boolean addressChanged = !address.equals(previousSaved);
		if (addressChanged) {
			settings.saveMobileAddress(address);
		}
		stopConnector();
		try {
			startConnector(address, previousPort > 0 ? previousPort : preferredPort);
		} catch (RuntimeException failed) {
			boolean restored = false;
			if (previousAddress != null) {
				try {
					startConnector(previousAddress, previousPort > 0 ? previousPort : 0);
					restored = true;
				} catch (RuntimeException restoreFailed) {
					failed.addSuppressed(restoreFailed);
				}
			}
			if (!restored) {
				lastError = message(failed);
			}
			if (addressChanged) {
				try {
					settings.saveMobileAddress(previousSaved);
				} catch (RuntimeException restoreSettingFailed) {
					failed.addSuppressed(restoreSettingFailed);
				}
			}
			throw failed instanceof MobileAccessException access ? access : new MobileAccessException(message(failed), failed);
		}
	}

	/** Returns the device token once; null for an expired, used or unknown code. */
	public synchronized String pair(String code, String userAgent) {
		Pairing current = pairing;
		if (current == null || !current.expiresAt().isAfter(Instant.now()) || !sameCode(current.code(), code)) {
			return null;
		}
		pairing = null;
		MobileDeviceStore.Issued issued = devices.add(labelFor(userAgent));
		return issued.token();
	}

	public synchronized List<DeviceView> devices() {
		return devices.devices().stream().map(device -> new DeviceView(device.id(), device.label(), device.pairedAt().toString())).toList();
	}

	public boolean revoke(String id) {
		return devices.revoke(id);
	}

	private void startConnector(String address, int port) {
		TomcatWebServer server = tomcat();
		Connector started = openConnector(server, address, port);
		connector = started;
		boundAddress = address;
		selectedAddress = address;
	}

	/**
	 * A fixed port can be taken by another program, so try a system-assigned port on the same address. A connector
	 * that reports no bound port is removed, never left behind as an "active" listener.
	 */
	private static Connector openConnector(TomcatWebServer server, String address, int port) {
		RuntimeException firstFailure = null;
		try {
			Connector candidate = addConnector(server, address, port);
			if (usable(candidate)) {
				return candidate;
			}
			removeAndDestroy(server, candidate);
			firstFailure = new MobileAccessException("手机访问的连接没有启动。");
		} catch (RuntimeException failed) {
			firstFailure = failed;
		}
		if (port == 0) {
			throw new MobileAccessException("手机访问的连接没有启动：" + reason(firstFailure), firstFailure);
		}
		try {
			Connector fallback = addConnector(server, address, 0);
			if (usable(fallback)) {
				return fallback;
			}
			removeAndDestroy(server, fallback);
			throw new MobileAccessException("手机访问的连接没有启动。");
		} catch (RuntimeException fallbackFailure) {
			if (firstFailure != null) {
				fallbackFailure.addSuppressed(firstFailure);
			}
			throw new MobileAccessException("手机访问的连接没有启动：" + reason(fallbackFailure), fallbackFailure);
		}
	}

	private static Connector addConnector(TomcatWebServer server, String address, int port) {
		Http11NioProtocol protocol = new Http11NioProtocol();
		try {
			protocol.setAddress(InetAddress.getByName(address));
		} catch (UnknownHostException failed) {
			throw new MobileAccessException("无法绑定局域网地址 " + address, failed);
		}
		protocol.setConnectionTimeout(20_000);
		Connector connector = new Connector(protocol);
		connector.setPort(port);
		connector.setURIEncoding("UTF-8");
		try {
			server.getTomcat().getService().addConnector(connector);
		} catch (RuntimeException failed) {
			removeAndDestroy(server, connector);
			throw failed;
		}
		return connector;
	}

	private static boolean usable(Connector connector) {
		return connector != null && connector.getState().isAvailable() && connector.getLocalPort() > 0;
	}

	private static void removeAndDestroy(TomcatWebServer server, Connector connector) {
		try {
			server.getTomcat().getService().removeConnector(connector);
		} catch (RuntimeException failed) {
			log.warn("移除手机访问连接器失败。", failed);
		}
		try {
			connector.destroy();
		} catch (LifecycleException | RuntimeException failed) {
			log.warn("释放手机访问连接器失败。", failed);
		}
	}

	private void stopConnector() {
		Connector current = connector;
		connector = null;
		boundAddress = null;
		if (current == null) {
			return;
		}
		TomcatWebServer server = tomcatOrNull();
		if (server != null) {
			removeAndDestroy(server, current);
		} else {
			try {
				current.destroy();
			} catch (LifecycleException | RuntimeException failed) {
				log.warn("关闭手机访问时没能释放连接器。", failed);
			}
		}
	}

	private TomcatWebServer tomcat() {
		TomcatWebServer server = tomcatOrNull();
		if (server == null) {
			throw new MobileAccessException("手机访问需要应用窗口里的服务，当前模式不支持。");
		}
		return server;
	}

	private TomcatWebServer tomcatOrNull() {
		WebServer webServer = spring instanceof WebServerApplicationContext webContext ? webContext.getWebServer() : null;
		return webServer instanceof TomcatWebServer tomcat ? tomcat : null;
	}

	/** Test seam: prove an address that cannot be bound leaves no connector behind. */
	Connector openConnectorForTest(String address, int port) {
		return openConnector(tomcat(), address, port);
	}

	private boolean writeMarker(Path marker, String label) {
		try {
			Files.createDirectories(marker.getParent());
			Files.writeString(marker, Instant.now().toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			return true;
		} catch (IOException failed) {
			log.warn("没能写下" + label + "。", failed);
			return false;
		}
	}

	private void clearMarker(Path marker, String label) {
		try {
			Files.deleteIfExists(marker);
		} catch (IOException failed) {
			log.warn("没能移除" + label + "。", failed);
		}
	}

	/** A named address that is no longer local is refused, so no entry point can silently widen the listener. */
	private static void requireAvailable(List<String> addresses, String wanted) {
		if (wanted != null && !wanted.isBlank() && !addresses.contains(wanted)) {
			throw new MobileAccessException("局域网地址 " + wanted + " 已不在线，手机访问保持原来的状态。请重新选择地址。");
		}
	}

	/** Only an address that still exists is reported, so a missing saved address never looks like a new default. */
	private static String availableAddress(List<String> addresses, String wanted) {
		return wanted != null && addresses.contains(wanted) ? wanted : null;
	}

	private static boolean sameCode(String expected, String candidate) {
		if (candidate == null) {
			return false;
		}
		return MessageDigest.isEqual(digest(expected), digest(candidate));
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required.", impossible);
		}
	}

	private static String newCode() {
		StringBuilder code = new StringBuilder(CODE_LENGTH);
		for (int index = 0; index < CODE_LENGTH; index++) {
			code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
		}
		return code.toString();
	}

	static String labelFor(String userAgent) {
		String agent = userAgent == null ? "" : userAgent;
		String platform = agent.contains("iPhone") ? "iPhone" : agent.contains("iPad") ? "iPad" : agent.contains("Android") ? "Android" : "";
		if (agent.contains("MicroMessenger")) {
			return platform.isEmpty() ? "微信" : "微信 · " + platform;
		}
		if (!platform.isEmpty()) {
			return "浏览器 · " + platform;
		}
		if (agent.contains("Windows")) {
			return "浏览器 · Windows";
		}
		return "手机";
	}

	private static String message(RuntimeException failure) {
		if (failure instanceof MobileAccessException access) {
			return access.getMessage();
		}
		return "手机访问没能打开：" + reason(failure);
	}

	private static String reason(Throwable failure) {
		String detail = failure == null ? null : failure.getMessage();
		return detail == null || detail.isBlank() ? "未知原因" : detail;
	}
}
