package com.yuutara.illustrationarchive.desktop;

import jakarta.annotation.PreDestroy;
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

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Optional LAN access for phones. The desktop server always stays on 127.0.0.1; turning this on adds a second Tomcat
 * connector on the LAN, and turning it off removes that connector, so the port really is unreachable again. Requests on
 * the LAN connector are allowed only with a device token created by scanning a one-time pairing code.
 *
 * <p>The connector is owned by the running Tomcat, not by a separate proxy: settings, cookies, images and the mobile
 * pages therefore behave exactly like the rest of the app.
 */
@Component
@Profile(DesktopServer.PROFILE)
public class MobileAccess {

	public static final String LAN_PORT_PROPERTY = "illustration-archive.desktop.mobile-port";
	public static final String DEVICE_COOKIE = "ia_mobile_device";
	static final Duration PAIRING_LIFETIME = Duration.ofMinutes(5);

	private static final Logger log = LoggerFactory.getLogger(MobileAccess.class);
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int CODE_LENGTH = 8;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final DesktopSettings settings;
	private final ApplicationContext spring;
	private final MobileDeviceStore devices;
	private final int preferredPort;

	private final Object lock = new Object();
	private volatile Connector connector;
	private volatile String selectedAddress;
	private volatile Pairing pairing;
	private volatile String lastError;

	public record DeviceView(String id, String label, String pairedAt) {
	}

	public record PairingView(String code, String url, String qr, String address, String expiresAt) {
	}

	/** {@code enabled} is what the user asked for; {@code active} is whether the LAN connector is up right now. */
	public record Status(boolean enabled, boolean active, int port, List<String> addresses, String address,
			String error, List<DeviceView> devices, PairingView pairing) {
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
			@Value("${" + LAN_PORT_PROPERTY + ":47393}") int preferredPort) {
		this.settings = settings;
		this.spring = spring;
		this.devices = devices;
		this.preferredPort = preferredPort < 0 || preferredPort > 65535 ? 47393 : preferredPort;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onReady() {
		if (!settings.mobileEnabled()) {
			return;
		}
		synchronized (lock) {
			try {
				startConnector();
				lastError = null;
			} catch (RuntimeException failed) {
				lastError = message(failed);
				log.warn("手机访问没能打开，应用继续只在本机提供图库。", failed);
			}
		}
	}

	@PreDestroy
	void close() {
		synchronized (lock) {
			stopConnector();
		}
	}

	/** The LAN port while the feature is up, or -1. The filter compares requests by this. */
	public int port() {
		Connector current = connector;
		return current == null ? -1 : current.getLocalPort();
	}

	public boolean acceptsDevice(String token) {
		return devices.accepts(token);
	}

	public synchronized Status status() {
		Connector current = connector;
		List<String> addresses = LanAddresses.siteLocal();
		String address = chooseAddress(addresses, selectedAddress);
		Pairing active = pairing;
		PairingView view = active == null || !active.expiresAt().isAfter(Instant.now())
				? null
				: new PairingView(active.code(), active.url(), active.qr(), active.address(), active.expiresAt().toString());
		return new Status(settings.mobileEnabled(), current != null, current == null ? -1 : current.getLocalPort(),
				addresses, address, lastError, devices.devices().stream()
						.map(device -> new DeviceView(device.id(), device.label(), device.pairedAt().toString()))
						.toList(), view);
	}

	public synchronized Status setEnabled(boolean enabled) {
		if (enabled) {
			settings.saveMobileEnabled(true);
			try {
				startConnector();
				lastError = null;
			} catch (RuntimeException failed) {
				lastError = message(failed);
				stopConnector();
				try {
					settings.saveMobileEnabled(false);
				} catch (RuntimeException alsoFailed) {
					// Keep the original failure; the next status will show that the switch is on but not listening.
					log.warn("手机访问没能打开，而且没能把开关写回关闭。", alsoFailed);
				}
				throw new MobileAccessException(lastError, failed);
			}
			return status();
		}
		// Stop first: "关闭" means the port is gone, not merely that the page hides a button.
		stopConnector();
		settings.saveMobileEnabled(false);
		pairing = null;
		lastError = null;
		return status();
	}

	public synchronized PairingView startPairing(String address) {
		Connector current = connector;
		if (current == null) {
			throw new MobileAccessException("手机访问还没有打开。");
		}
		List<String> addresses = LanAddresses.siteLocal();
		String chosen = chooseAddress(addresses, address);
		if (chosen == null) {
			throw new MobileAccessException("没有找到可用的局域网地址，请确认电脑和手机连在同一个 Wi-Fi。");
		}
		String code = newCode();
		String url = "http://" + chosen + ":" + current.getLocalPort() + "/m/pair?code=" + code;
		Pairing created = new Pairing(code, url, MobileQr.svgDataUri(url), chosen, Instant.now().plus(PAIRING_LIFETIME));
		pairing = created;
		selectedAddress = chosen;
		return new PairingView(created.code(), created.url(), created.qr(), created.address(), created.expiresAt().toString());
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

	private void startConnector() {
		if (connector != null) {
			return;
		}
		WebServer webServer = spring instanceof WebServerApplicationContext webContext ? webContext.getWebServer() : null;
		if (!(webServer instanceof TomcatWebServer tomcatWebServer)) {
			throw new MobileAccessException("手机访问需要应用窗口里的服务，当前模式不支持。");
		}
		try {
			connector = addConnector(tomcatWebServer, preferredPort == 0 ? 0 : preferredPort);
		} catch (RuntimeException preferredFailure) {
			if (preferredPort == 0) {
				throw preferredFailure;
			}
			// A fixed port can be taken by another program; a free one still works, and the QR shows it.
			log.info("手机访问的固定端口不可用，改用系统分配的端口。", preferredFailure);
			connector = addConnector(tomcatWebServer, 0);
		}
		selectedAddress = chooseAddress(LanAddresses.siteLocal(), selectedAddress);
	}

	private static Connector addConnector(TomcatWebServer tomcatWebServer, int port) {
		Http11NioProtocol protocol = new Http11NioProtocol();
		try {
			protocol.setAddress(InetAddress.getByName("0.0.0.0"));
		} catch (java.net.UnknownHostException impossible) {
			throw new IllegalStateException(impossible);
		}
		protocol.setConnectionTimeout(20_000);
		Connector connector = new Connector(protocol);
		connector.setPort(port);
		connector.setURIEncoding("UTF-8");
		try {
			tomcatWebServer.getTomcat().getService().addConnector(connector);
		} catch (RuntimeException failed) {
			try {
				tomcatWebServer.getTomcat().getService().removeConnector(connector);
			} catch (RuntimeException ignored) {
				// It never started; nothing else to release.
			}
			try {
				connector.destroy();
			} catch (org.apache.catalina.LifecycleException | RuntimeException ignored) {
				// Best effort while already failing.
			}
			throw failed;
		}
		return connector;
	}

	private void stopConnector() {
		Connector current = connector;
		connector = null;
		if (current == null) {
			return;
		}
		WebServer webServer = spring instanceof WebServerApplicationContext webContext ? webContext.getWebServer() : null;
		if (webServer instanceof TomcatWebServer tomcatWebServer) {
			try {
				tomcatWebServer.getTomcat().getService().removeConnector(current);
			} catch (RuntimeException failed) {
				log.warn("关闭手机访问时没能从服务里移除连接器。", failed);
			}
		}
		try {
			current.destroy();
		} catch (org.apache.catalina.LifecycleException | RuntimeException failed) {
			log.warn("关闭手机访问时没能释放连接器。", failed);
		}
	}

	private String chooseAddress(List<String> addresses, String wanted) {
		if (wanted != null && addresses.contains(wanted)) {
			return wanted;
		}
		return addresses.isEmpty() ? null : addresses.get(0);
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
		String detail = failure.getMessage();
		return detail == null || detail.isBlank() ? "手机访问没能打开。" : "手机访问没能打开：" + detail;
	}
}
