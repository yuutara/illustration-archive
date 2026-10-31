package com.yuutara.illustrationarchive.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Non-secret preferences from Settings, in {@code <home>\config\settings.properties}. Tokens and keys live elsewhere. */
@Component
@Profile(DesktopServer.PROFILE)
public class DesktopSettings {

	private static final Pattern PROXY = Pattern.compile("([A-Za-z0-9.-]+):(\\d{1,5})");
	private static final String X_CLIENT_ID = "x.client-id";
	private static final String PROXY_ADDRESS = "proxy.address";
	private static final String CLOSE_ACTION = "window.close-action";

	private final Path file;
	private final Properties values = new Properties();
	private final DesktopProxySelector proxySelector = DesktopProxySelector.installed();

	public DesktopSettings(@Value("${" + DesktopServer.HOME_PROPERTY + "}") String home) {
		file = Path.of(home).resolve("config").resolve("settings.properties");
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				values.load(reader);
			} catch (IOException | IllegalArgumentException unreadable) {
				throw new IllegalStateException("Cannot read " + file, unreadable);
			}
		}
		proxySelector.useManual(proxyAddress(values.getProperty(PROXY_ADDRESS, "")));
	}

	/** For the launcher, which needs this to open the window before the Spring context (and this bean) exists. */
	static String savedProxy(Path home) {
		Properties saved = new Properties();
		try (Reader reader = Files.newBufferedReader(home.resolve("config").resolve("settings.properties"), StandardCharsets.UTF_8)) {
			saved.load(reader);
		} catch (IOException | IllegalArgumentException absentOrUnreadable) {
			return ""; // The bean reports an unreadable file properly as the app starts.
		}
		return saved.getProperty(PROXY_ADDRESS, "");
	}

	public synchronized String xClientId() {
		return values.getProperty(X_CLIENT_ID, "");
	}

	public synchronized void saveXClientId(String clientId) {
		values.setProperty(X_CLIENT_ID, clientId);
		write();
	}

	/** Empty means "follow the Windows proxy settings". */
	public synchronized String proxy() {
		return values.getProperty(PROXY_ADDRESS, "");
	}

	public synchronized void saveProxy(String address) {
		InetSocketAddress parsed = proxyAddress(address);
		values.setProperty(PROXY_ADDRESS, address);
		write();
		proxySelector.useManual(parsed);
	}

	public synchronized CloseAction closeAction() {
		return CloseAction.stored(values.getProperty(CLOSE_ACTION));
	}

	public synchronized void saveCloseAction(CloseAction action) {
		values.setProperty(CLOSE_ACTION, action.stored());
		write();
	}

	private static InetSocketAddress proxyAddress(String address) {
		if (address.isEmpty()) {
			return null;
		}
		Matcher matcher = PROXY.matcher(address);
		if (!matcher.matches() || Integer.parseInt(matcher.group(2)) == 0 || Integer.parseInt(matcher.group(2)) > 65535) {
			throw new IllegalArgumentException("Proxy must look like 127.0.0.1:7890.");
		}
		return InetSocketAddress.createUnresolved(matcher.group(1), Integer.parseInt(matcher.group(2)));
	}

	private void write() {
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".settings-", ".tmp");
			try {
				try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
					values.store(writer, "Illustration Archive settings");
				}
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot save settings.", e);
		}
	}
}
