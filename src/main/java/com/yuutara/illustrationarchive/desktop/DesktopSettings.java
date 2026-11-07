package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.service.XLikeSyncReadLimit;
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
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Non-secret preferences from Settings, in {@code <home>\config\settings.properties}. Tokens and keys live elsewhere. */
@Component
@Profile(DesktopServer.PROFILE)
public class DesktopSettings implements XLikeSyncReadLimit {

	/** The choices Settings offers for "read at most this many Likes per sync". Every one fits the page budget. */
	public static final List<Integer> X_READ_LIMITS = List.of(25, 50, 100, 200);
	static final int DEFAULT_X_READ_LIMIT = 50;

	private static final Pattern PROXY = Pattern.compile("([A-Za-z0-9.-]+):(\\d{1,5})");
	private static final String X_CLIENT_ID = "x.client-id";
	private static final String PROXY_ADDRESS = "proxy.address";
	private static final String CLOSE_ACTION = "window.close-action";
	private static final String X_READ_LIMIT = "x.sync.read-limit";
	private static final String X_VERIFIED_LOGIN = "x.verified-login";
	private static final String X_VERIFIED_AT = "x.verified-at";
	private static final String X_VERIFIED_ACCOUNT = "x.verified-account";
	private static final String AI_VERIFIED_KEY = "ai.verified-key";
	private static final String AI_VERIFIED_AT = "ai.verified-at";

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
		change(() -> values.setProperty(X_CLIENT_ID, clientId));
	}

	/** A value edited by hand into something Settings never offers counts as unset, so a sync never reads more by accident. */
	@Override
	public synchronized Integer reads() {
		try {
			int saved = Integer.parseInt(values.getProperty(X_READ_LIMIT, "").trim());
			return X_READ_LIMITS.contains(saved) ? saved : DEFAULT_X_READ_LIMIT;
		} catch (NumberFormatException unset) {
			return DEFAULT_X_READ_LIMIT;
		}
	}

	public synchronized void saveReads(int reads) {
		if (!X_READ_LIMITS.contains(reads)) {
			throw new IllegalArgumentException("Read limit must be one of " + X_READ_LIMITS + ".");
		}
		change(() -> values.setProperty(X_READ_LIMIT, Integer.toString(reads)));
	}

	/** The login the saved X proof is about. {@link DesktopEvidence} decides whether the proof applies. */
	synchronized String xVerifiedLogin() {
		return values.getProperty(X_VERIFIED_LOGIN);
	}

	synchronized String xVerifiedAt() {
		return values.getProperty(X_VERIFIED_AT);
	}

	/** Null when X gave no name worth showing, which does not make the sync any less real. */
	synchronized String xVerifiedAccount() {
		return values.getProperty(X_VERIFIED_ACCOUNT);
	}

	synchronized void saveXVerified(String login, String account, Instant at) {
		change(() -> {
			values.setProperty(X_VERIFIED_LOGIN, login);
			values.setProperty(X_VERIFIED_AT, at.toString());
			if (account == null) values.remove(X_VERIFIED_ACCOUNT);
			else values.setProperty(X_VERIFIED_ACCOUNT, account);
		});
	}

	synchronized void forgetXVerified() {
		change(() -> {
			values.remove(X_VERIFIED_LOGIN);
			values.remove(X_VERIFIED_AT);
			values.remove(X_VERIFIED_ACCOUNT);
		});
	}

	/** The key the saved analysis proof is about, as a fingerprint. */
	synchronized String aiVerifiedKey() {
		return values.getProperty(AI_VERIFIED_KEY);
	}

	synchronized String aiVerifiedAt() {
		return values.getProperty(AI_VERIFIED_AT);
	}

	synchronized void saveAiVerified(String key, Instant at) {
		change(() -> {
			values.setProperty(AI_VERIFIED_KEY, key);
			values.setProperty(AI_VERIFIED_AT, at.toString());
		});
	}

	synchronized void forgetAiVerified() {
		change(() -> {
			values.remove(AI_VERIFIED_KEY);
			values.remove(AI_VERIFIED_AT);
		});
	}

	/** Empty means "follow the Windows proxy settings". */
	public synchronized String proxy() {
		return values.getProperty(PROXY_ADDRESS, "");
	}

	public synchronized void saveProxy(String address) {
		InetSocketAddress parsed = proxyAddress(address);
		change(() -> values.setProperty(PROXY_ADDRESS, address));
		proxySelector.useManual(parsed);
	}

	public synchronized CloseAction closeAction() {
		return CloseAction.stored(values.getProperty(CLOSE_ACTION));
	}

	public synchronized void saveCloseAction(CloseAction action) {
		change(() -> values.setProperty(CLOSE_ACTION, action.stored()));
	}

	/** What the page reads back must be what is on disk, so a value that could not be saved does not stay in memory. */
	private void change(Runnable edit) {
		Properties before = new Properties();
		before.putAll(values);
		edit.run();
		try {
			write();
		} catch (UncheckedIOException failed) {
			values.clear();
			values.putAll(before);
			throw failed;
		}
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
