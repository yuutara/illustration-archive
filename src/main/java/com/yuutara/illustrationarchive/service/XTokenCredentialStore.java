package com.yuutara.illustrationarchive.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;

@Component
public class XTokenCredentialStore {
	private final Path path;

	public XTokenCredentialStore(@Value("${x.oauth.credential-file:./config/x-oauth-tokens.properties}") String file) {
		this.path = Path.of(file).toAbsolutePath().normalize();
	}

	Credentials load() {
		if (!Files.exists(path)) {
			throw new XApiException("X OAuth credentials are missing. Generate access and refresh tokens in X Developer Console and fill the local credential file.", null);
		}
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			values.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			throw new XApiException("Cannot read the local X OAuth credential file.", null);
		}
		String accessToken = values.getProperty("access_token", "").trim();
		String refreshToken = values.getProperty("refresh_token", "").trim();
		if (refreshToken.isBlank()) {
			throw new XApiException("X OAuth refresh token is missing. Generate a new token pair in X Developer Console.", null);
		}
		if (containsWhitespace(accessToken) || containsWhitespace(refreshToken)) {
			throw new XApiException("X OAuth credential file contains a malformed token.", null);
		}
		Instant expiresAt = null;
		String expiry = values.getProperty("expires_at", "").trim();
		if (!expiry.isBlank()) {
			try {
				expiresAt = Instant.parse(expiry);
			} catch (DateTimeParseException e) {
				throw new XApiException("X OAuth expires_at must be an ISO-8601 UTC instant or blank.", null);
			}
		}
		return new Credentials(accessToken, refreshToken, expiresAt);
	}

	void save(Credentials credentials) {
		Path parent = path.getParent();
		Path temporary = null;
		try {
			Files.createDirectories(parent);
			temporary = Files.createTempFile(parent, ".x-oauth-tokens-", ".tmp");
			preservePermissions(temporary);
			Properties values = new Properties();
			values.setProperty("access_token", credentials.accessToken());
			values.setProperty("refresh_token", credentials.refreshToken());
			values.setProperty("expires_at", credentials.expiresAt() == null ? "" : credentials.expiresAt().toString());
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				values.store(writer, "X OAuth credentials - keep private");
			}
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			throw new XApiException("Cannot atomically update X OAuth credentials. Keep the application running, fix the file location, and retry; restarting may require new tokens from X Developer Console.", null);
		} catch (IOException e) {
			throw new XApiException("Cannot save refreshed X OAuth credentials. Keep the application running, fix file permissions, and retry; restarting may require new tokens from X Developer Console.", null);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// The original credential file is left in place if replacement failed.
				}
			}
		}
	}

	private void preservePermissions(Path temporary) throws IOException {
		PosixFileAttributeView posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
		if (posix != null) {
			posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
			return;
		}
		if (Files.exists(path)) {
			AclFileAttributeView original = Files.getFileAttributeView(path, AclFileAttributeView.class);
			AclFileAttributeView replacement = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
			if (original != null && replacement != null) replacement.setAcl(original.getAcl());
		}
	}

	static boolean containsWhitespace(String value) {
		return value.chars().anyMatch(Character::isWhitespace);
	}

	record Credentials(String accessToken, String refreshToken, Instant expiresAt) {
	}
}
