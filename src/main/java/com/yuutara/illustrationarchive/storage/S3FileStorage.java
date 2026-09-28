package com.yuutara.illustrationarchive.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** S3 original-media adapter. A bounded temporary file gives the synchronous SDK a known upload length. */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3FileStorage implements FileStorage {
	private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
	private final S3ObjectStore objects;

	S3FileStorage(S3ObjectStore objects) {
		this.objects = objects;
	}

	@Override
	public StoredFile store(String originalFilename, InputStream source) {
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new FileStorageValidationException("Uploaded file must have an original filename.");
		}
		int dot = originalFilename.lastIndexOf('.');
		if (dot <= 0 || dot == originalFilename.length() - 1) {
			throw new FileStorageValidationException("Uploaded file must have a supported extension.");
		}
		String extension = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
		if (!extension.equals("jpg") && !extension.equals("jpeg")
				&& !extension.equals("png") && !extension.equals("gif")) {
			throw new FileStorageValidationException("Only JPEG, PNG, and GIF files are supported.");
		}
		Path temporaryFile = null;
		String key = YearMonth.now() + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
		MessageDigest digest = sha256Digest();
		try (InputStream input = new DigestInputStream(source, digest)) {
			byte[] signature = input.readNBytes(8);
			String mimeType = detectMimeType(signature);
			if ((!mimeType.equals("image/jpeg") || !(extension.equals("jpg") || extension.equals("jpeg")))
					&& (!mimeType.equals("image/png") || !extension.equals("png"))
					&& (!mimeType.equals("image/gif") || !extension.equals("gif"))) {
				throw new FileStorageValidationException("Filename extension does not match the image content.");
			}
			temporaryFile = Files.createTempFile("archive-s3-upload-", ".tmp");
			long size = signature.length;
			try (var output = Files.newOutputStream(temporaryFile)) {
				output.write(signature);
				byte[] buffer = new byte[8192];
				int count;
				while ((count = input.read(buffer)) != -1) {
					size += count;
					if (size > MAX_FILE_SIZE) {
						throw new FileStorageValidationException("Uploaded file must not exceed 50 MB.");
					}
					output.write(buffer, 0, count);
				}
			}
			try {
				objects.put(key, temporaryFile, mimeType);
			} catch (RuntimeException failure) {
				try {
					objects.delete(key);
				} catch (RuntimeException cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
			return new StoredFile(originalFilename, key, mimeType, size, HexFormat.of().formatHex(digest.digest()));
		} catch (IOException exception) {
			throw new FileStorageException("Failed to store uploaded file.", exception);
		} finally {
			if (temporaryFile != null) {
				try { Files.deleteIfExists(temporaryFile); } catch (IOException ignored) { }
			}
		}
	}

	@Override
	public Resource load(String storageKey) {
		return objects.load(storageKey);
	}

	@Override
	public void delete(String storageKey) {
		objects.delete(storageKey);
	}

	@Override
	public String calculateSha256(String storageKey) {
		MessageDigest digest = sha256Digest();
		try (InputStream input = new DigestInputStream(load(storageKey).getInputStream(), digest)) {
			byte[] buffer = new byte[8192];
			while (input.read(buffer) != -1) { }
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException exception) {
			throw new FileStorageException("Failed to read stored file.", exception);
		}
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private static String detectMimeType(byte[] signature) {
		if (signature.length >= 3 && (signature[0] & 0xff) == 0xff
				&& (signature[1] & 0xff) == 0xd8 && (signature[2] & 0xff) == 0xff) return "image/jpeg";
		if (signature.length >= 8 && (signature[0] & 0xff) == 0x89 && signature[1] == 'P'
				&& signature[2] == 'N' && signature[3] == 'G' && signature[4] == 13
				&& signature[5] == 10 && signature[6] == 26 && signature[7] == 10) return "image/png";
		if (signature.length >= 6 && signature[0] == 'G' && signature[1] == 'I'
				&& signature[2] == 'F' && signature[3] == '8'
				&& (signature[4] == '7' || signature[4] == '9') && signature[5] == 'a') return "image/gif";
		throw new FileStorageValidationException("Uploaded file is not a supported image format.");
	}
}
