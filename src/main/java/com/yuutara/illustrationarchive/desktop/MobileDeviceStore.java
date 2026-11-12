package com.yuutara.illustrationarchive.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Paired phones, stored as SHA-256 token hashes in {@code <home>\config\mobile-devices.properties}. The device only
 * holds the raw token in an HttpOnly cookie; this file cannot be used to read the library. The map in memory is the
 * one requests are checked against, so revoking a device takes effect on the next request.
 */
@Component
@Profile(DesktopServer.PROFILE)
class MobileDeviceStore {

	public record Device(String id, String label, Instant pairedAt) {
	}

	record Issued(String token, Device device) {
	}

	private static final Logger log = LoggerFactory.getLogger(MobileDeviceStore.class);
	private static final String LABEL = ".label";
	private static final String PAIRED_AT = ".paired-at";

	private final Path file;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Device> devices = new LinkedHashMap<>();

	MobileDeviceStore(@Value("${" + DesktopServer.HOME_PROPERTY + "}") String home) {
		file = Path.of(home).resolve("config").resolve("mobile-devices.properties");
		load();
	}

	synchronized List<Device> devices() {
		return List.copyOf(devices.values());
	}

	/** Creates a token the phone keeps; only its hash is written, and a failed write leaves no usable device behind. */
	synchronized Issued add(String label) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		String hash = hash(token);
		Device device = new Device(hash.substring(0, 12), safeLabel(label), Instant.now());
		devices.put(hash, device);
		try {
			save();
		} catch (RuntimeException failed) {
			devices.remove(hash);
			throw failed;
		}
		return new Issued(token, device);
	}

	/** False after the phone is revoked, even while its old cookie is still on the phone. */
	synchronized boolean accepts(String token) {
		return token != null && !token.isBlank() && devices.containsKey(hash(token));
	}

	/** Removes the device whose public id matches; the raw token is never stored, so it is gone at once. */
	synchronized boolean revoke(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}
		String wanted = id.trim().toLowerCase(java.util.Locale.ROOT);
		// The public id is a 12-character prefix; refusing shorter input stops one request revoking many phones.
		if (!wanted.matches("[0-9a-f]{12,64}")) {
			return false;
		}
		Map<String, Device> before = new LinkedHashMap<>(devices);
		boolean removed = devices.keySet().removeIf(hash -> hash.startsWith(wanted));
		if (!removed) {
			return false;
		}
		try {
			save();
		} catch (RuntimeException failed) {
			// The file still lists the phone, so the running process must keep it too: the list, the next request and a
			// restart then all say the same thing, and the revoke can simply be retried.
			devices.clear();
			devices.putAll(before);
			throw failed;
		}
		return true;
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		Properties saved = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			saved.load(reader);
		} catch (IOException | IllegalArgumentException unreadable) {
			// Phone access is optional: a damaged list must not stop the local library from opening. The next pairing
			// writes a fresh file and replaces it.
			log.warn("无法读取已配对的手机列表，手机访问会从空列表开始。", unreadable);
			devices.clear();
			return;
		}
		List<String> hashes = new ArrayList<>();
		for (String key : saved.stringPropertyNames()) {
			if (key.startsWith("device.") && key.endsWith(LABEL)) {
				hashes.add(key.substring("device.".length(), key.length() - LABEL.length()));
			}
		}
		for (String hash : hashes) {
			if (!hash.matches("[0-9a-f]{64}")) {
				continue;
			}
			String label = saved.getProperty("device." + hash + LABEL, "手机");
			Instant pairedAt;
			try {
				pairedAt = Instant.parse(saved.getProperty("device." + hash + PAIRED_AT, ""));
			} catch (java.time.format.DateTimeParseException unreadable) {
				continue;
			}
			devices.put(hash, new Device(hash.substring(0, 12), safeLabel(label), pairedAt));
		}
	}

	private void save() {
		Properties out = new Properties();
		devices.forEach((hash, device) -> {
			out.setProperty("device." + hash + LABEL, device.label());
			out.setProperty("device." + hash + PAIRED_AT, device.pairedAt().toString());
		});
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".mobile-devices-", ".tmp");
			try {
				try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
					out.store(writer, "Illustration Archive paired phones");
				}
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException failure) {
			throw new UncheckedIOException("Cannot save paired phones.", failure);
		}
	}

	private static String safeLabel(String label) {
		String cleaned = label == null ? "" : label.replaceAll("\\p{Cntrl}", " ").strip();
		if (cleaned.isEmpty()) {
			cleaned = "手机";
		}
		return cleaned.length() <= 60 ? cleaned : cleaned.substring(0, 60);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required.", impossible);
		}
	}
}
