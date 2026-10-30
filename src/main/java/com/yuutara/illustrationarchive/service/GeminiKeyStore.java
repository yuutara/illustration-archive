package com.yuutara.illustrationarchive.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
