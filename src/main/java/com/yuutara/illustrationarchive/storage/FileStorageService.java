package com.yuutara.illustrationarchive.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class FileStorageService {

	private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
	private static final int SIGNATURE_LENGTH = 8;
	private static final int STREAM_BUFFER_SIZE = 8192;
	private static final DateTimeFormatter STORAGE_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

	private final Path storageRoot;

	public FileStorageService(@Value("${illustration-archive.storage.root-dir}") String storageRootDir) {
		this.storageRoot = Path.of(storageRootDir).toAbsolutePath().normalize();
	}

	public StoredFile store(MultipartFile file) {
		validateFile(file);

		String originalFilename = file.getOriginalFilename();
		// Preserve extension validation before opening the MultipartFile stream.
		extractExtension(originalFilename);
		try {
			return store(originalFilename, file.getInputStream());
		} catch (IOException exception) {
			throw new FileStorageException("Failed to store uploaded file.", exception);
		}
	}

	public StoredFile store(String originalFilename, InputStream source) {
		validateFilename(originalFilename);
		String extension = extractExtension(originalFilename);
		ImageFormat actualFormat;
		Path temporaryFile = null;
		MessageDigest sha256Digest = createSha256Digest();

		try (InputStream inputStream = new DigestInputStream(source, sha256Digest)) {
			byte[] signature = inputStream.readNBytes(SIGNATURE_LENGTH);
			actualFormat = detectFormat(signature);
			validateExtensionMatchesFormat(extension, actualFormat);

			String storageKey = createStorageKey(extension);
			Path targetPath = resolveStoragePath(storageKey);
			Path monthDirectory = targetPath.getParent();
			Files.createDirectories(monthDirectory);

			if (Files.exists(targetPath)) {
				throw new FileStorageException("A generated storage path already exists.");
			}

			temporaryFile = Files.createTempFile(monthDirectory, ".upload-", ".tmp");
			long savedSize = writeFile(inputStream, signature, temporaryFile);
			moveWithoutReplacing(temporaryFile, targetPath);
			temporaryFile = null;

			return new StoredFile(
					originalFilename,
					storageKey,
					actualFormat.mimeType(),
					savedSize,
					formatSha256(sha256Digest)
			);
		} catch (IOException exception) {
			throw new FileStorageException("Failed to store uploaded file.", exception);
		} finally {
			deleteTemporaryFile(temporaryFile);
		}
	}

	public void delete(String storageKey) {
		Path filePath = resolveStoragePath(storageKey);
		try {
			Files.deleteIfExists(filePath);
		} catch (IOException exception) {
			throw new FileStorageException("Failed to delete stored file.", exception);
		}
	}

	public Resource load(String storageKey) {
		Path filePath = resolveStoragePath(storageKey);
		if (!Files.isRegularFile(filePath)) {
			throw new FileStorageException("Stored file does not exist or is not a regular file.");
		}
		try {
			Path realRoot = storageRoot.toRealPath();
			Path realFile = filePath.toRealPath();
			if (!realFile.startsWith(realRoot)) {
				throw new FileStorageValidationException("Stored file must stay within the configured storage root.");
			}
			return new FileSystemResource(realFile);
		} catch (IOException exception) {
			throw new FileStorageException("Failed to locate stored file.", exception);
		}
	}

	public String calculateSha256(String storageKey) {
		Resource resource = load(storageKey);
		MessageDigest sha256Digest = createSha256Digest();

		try (InputStream inputStream = new DigestInputStream(resource.getInputStream(), sha256Digest)) {
			byte[] buffer = new byte[STREAM_BUFFER_SIZE];
			while (inputStream.read(buffer) != -1) {
				// DigestInputStream updates the hash as each chunk is read.
			}
			return formatSha256(sha256Digest);
		} catch (IOException exception) {
			throw new FileStorageException("Failed to read stored file.", exception);
		}
	}

	private void validateFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new FileStorageValidationException("Uploaded file must not be empty.");
		}
		validateFilename(file.getOriginalFilename());
		if (file.getSize() > MAX_FILE_SIZE) {
			throw new FileStorageValidationException("Uploaded file must not exceed 50 MB.");
		}
	}

	private void validateFilename(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			throw new FileStorageValidationException("Uploaded file must have an original filename.");
		}
	}

	private String extractExtension(String originalFilename) {
		int lastDotIndex = originalFilename.lastIndexOf('.');
		if (lastDotIndex <= 0 || lastDotIndex == originalFilename.length() - 1) {
			throw new FileStorageValidationException("Uploaded file must have a supported extension.");
		}

		String extension = originalFilename.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
		return switch (extension) {
			case "jpg", "jpeg", "png", "gif" -> extension;
			default -> throw new FileStorageValidationException("Only JPEG, PNG, and GIF files are supported.");
		};
	}

	private ImageFormat detectFormat(byte[] signature) {
		if (signature.length >= 3
				&& (signature[0] & 0xFF) == 0xFF
				&& (signature[1] & 0xFF) == 0xD8
				&& (signature[2] & 0xFF) == 0xFF) {
			return ImageFormat.JPEG;
		}
		if (signature.length >= 8
				&& (signature[0] & 0xFF) == 0x89
				&& signature[1] == 0x50
				&& signature[2] == 0x4E
				&& signature[3] == 0x47
				&& signature[4] == 0x0D
				&& signature[5] == 0x0A
				&& signature[6] == 0x1A
				&& signature[7] == 0x0A) {
			return ImageFormat.PNG;
		}
		if (signature.length >= 6
				&& signature[0] == 'G'
				&& signature[1] == 'I'
				&& signature[2] == 'F'
				&& ((signature[3] == '8' && signature[4] == '7' && signature[5] == 'a')
				|| (signature[3] == '8' && signature[4] == '9' && signature[5] == 'a'))) {
			return ImageFormat.GIF;
		}

		throw new FileStorageValidationException("Uploaded file is not a supported image format.");
	}

	private void validateExtensionMatchesFormat(String extension, ImageFormat actualFormat) {
		if (!actualFormat.matchesExtension(extension)) {
			throw new FileStorageValidationException("Filename extension does not match the image content.");
		}
	}

	private String createStorageKey(String extension) {
		String month = YearMonth.now().format(STORAGE_MONTH_FORMAT);
		String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
		return month + "/" + filename;
	}

	Path resolveStoragePath(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new FileStorageValidationException("Storage key must not be blank.");
		}

		Path keyPath;
		try {
			keyPath = Path.of(storageKey);
		} catch (InvalidPathException exception) {
			throw new FileStorageValidationException("Storage key is not a valid path.");
		}
		if (keyPath.isAbsolute()) {
			throw new FileStorageValidationException("Storage key must be a relative path.");
		}
		Path resolvedPath = storageRoot.resolve(keyPath).normalize();
		if (!resolvedPath.startsWith(storageRoot)) {
			throw new FileStorageValidationException("Storage key must stay within the configured storage root.");
		}
		return resolvedPath;
	}

	private long writeFile(InputStream inputStream, byte[] signature, Path temporaryFile) throws IOException {
		try (var outputStream = Files.newOutputStream(temporaryFile)) {
			outputStream.write(signature);
			long savedSize = signature.length;
			byte[] buffer = new byte[STREAM_BUFFER_SIZE];
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				savedSize += bytesRead;
				if (savedSize > MAX_FILE_SIZE) {
					throw new FileStorageValidationException("Uploaded file must not exceed 50 MB.");
				}
				outputStream.write(buffer, 0, bytesRead);
			}
			return savedSize;
		}
	}

	private MessageDigest createSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private String formatSha256(MessageDigest sha256Digest) {
		return HexFormat.of().formatHex(sha256Digest.digest());
	}

	private void moveWithoutReplacing(Path temporaryFile, Path targetPath) throws IOException {
		Files.move(temporaryFile, targetPath);
	}

	private void deleteTemporaryFile(Path temporaryFile) {
		if (temporaryFile == null) {
			return;
		}
		try {
			Files.deleteIfExists(temporaryFile);
		} catch (IOException ignored) {
			// The original storage failure is more useful to the caller than cleanup failure.
		}
	}

	private enum ImageFormat {
		JPEG("image/jpeg"),
		PNG("image/png"),
		GIF("image/gif");

		private final String mimeType;

		ImageFormat(String mimeType) {
			this.mimeType = mimeType;
		}

		private String mimeType() {
			return mimeType;
		}

		private boolean matchesExtension(String extension) {
			return switch (this) {
				case JPEG -> extension.equals("jpg") || extension.equals("jpeg");
				case PNG -> extension.equals("png");
				case GIF -> extension.equals("gif");
			};
		}
	}
}
