package com.yuutara.illustrationarchive.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Where the Gemini key comes from. Server deployments configure it; the desktop app sets
 * {@code gemini.credential-file} so a user can paste their own key in Settings and use it without a restart.
 */
@Component
public class GeminiKeyStore {
	private final String configuredKey;
	private final Path file;

	public GeminiKeyStore(@Value("${gemini.api-key:}") String configuredKey,
			@Value("${gemini.credential-file:}") String file) {
		this.configuredKey = configuredKey;
		this.file = file.isBlank() ? null : Path.of(file).toAbsolutePath().normalize();
	}

	public String apiKey() {
		if (file == null) return configuredKey;
		try {
			return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read the local Gemini key file.", e);
		}
	}

	/** Whether a key is on file, without reading it: Settings must still open, and offer Remove, if it cannot be read. */
	public boolean saved() {
		if (file == null) return !configuredKey.isBlank();
		try {
			return Files.exists(file) && Files.size(file) > 0;
		} catch (IOException e) {
			return true;
		}
	}

	/** Tells keys apart without keeping one anywhere new; empty when no key can be read. */
	public String fingerprint() {
		try {
			String key = apiKey();
			if (key.isBlank()) return "";
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		} catch (UncheckedIOException unreadable) {
			return "";
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}

	public void save(String key) {
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), ".gemini-", ".tmp");
			try {
				Files.writeString(temporary, key, StandardCharsets.UTF_8);
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot save the local Gemini key file.", e);
		}
	}

	public void clear() {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot remove the local Gemini key file.", e);
		}
	}
}
