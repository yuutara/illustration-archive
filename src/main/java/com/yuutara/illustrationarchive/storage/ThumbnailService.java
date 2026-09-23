package com.yuutara.illustrationarchive.storage;

import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;

@Service
public class ThumbnailService {

	private static final int MAX_DIMENSION = 600;
	private static final float JPEG_QUALITY = 0.85f;
	private static final String THUMBNAIL_DIRECTORY = "thumbnails/";

	private final FileStorageService fileStorageService;

	public ThumbnailService(FileStorageService fileStorageService) {
		this.fileStorageService = fileStorageService;
	}

	public String generateThumbnail(String storageKey) {
		rejectParentPathSegments(storageKey);
		Path sourcePath = fileStorageService.resolveStoragePath(storageKey);
		String format = imageFormat(sourcePath);
		String thumbnailKey = thumbnailStorageKey(storageKey);
		Path thumbnailPath = fileStorageService.resolveStoragePath(thumbnailKey);

		if (Files.exists(thumbnailPath, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isRegularFile(thumbnailPath, LinkOption.NOFOLLOW_LINKS)) {
				return thumbnailKey;
			}
			throw new FileStorageException("Thumbnail path exists and is not a regular file.");
		}

		if (!Files.isRegularFile(sourcePath)) {
			throw new FileStorageException("Stored file does not exist or is not a regular file.");
		}

		BufferedImage originalImage = readImage(sourcePath);
		Path temporaryFile = null;
		try {
			Files.createDirectories(thumbnailPath.getParent());
			temporaryFile = Files.createTempFile(thumbnailPath.getParent(), ".thumbnail-", ".tmp");

			if (originalImage.getWidth() <= MAX_DIMENSION && originalImage.getHeight() <= MAX_DIMENSION) {
				Files.copy(sourcePath, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
			} else {
				BufferedImage resizedImage = resize(originalImage, format);
				writeImage(resizedImage, format, temporaryFile);
			}

			try {
				Files.move(temporaryFile, thumbnailPath);
				temporaryFile = null;
			} catch (FileAlreadyExistsException exception) {
				return thumbnailKey;
			}
			return thumbnailKey;
		} catch (ThumbnailGenerationException | FileStorageException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new FileStorageException("Failed to write thumbnail file.", exception);
		} finally {
			deleteTemporaryFile(temporaryFile);
		}
	}

	public void deleteThumbnail(String storageKey) {
		rejectParentPathSegments(storageKey);
		fileStorageService.resolveStoragePath(storageKey);
		fileStorageService.delete(thumbnailStorageKey(storageKey));
	}

	private String thumbnailStorageKey(String storageKey) {
		return THUMBNAIL_DIRECTORY + storageKey;
	}

	private void rejectParentPathSegments(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			return;
		}

		Path keyPath;
		try {
			keyPath = Path.of(storageKey);
		} catch (InvalidPathException exception) {
			return;
		}
		for (Path segment : keyPath) {
			if (segment.toString().equals("..")) {
				throw new FileStorageValidationException("Storage key must not contain parent path segments.");
			}
		}
	}

	private String imageFormat(Path sourcePath) {
		Path filename = sourcePath.getFileName();
		String name = filename == null ? "" : filename.toString();
		int extensionIndex = name.lastIndexOf('.');
		String extension = extensionIndex < 0 ? "" : name.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
		return switch (extension) {
			case "jpg", "jpeg" -> "jpeg";
			case "png" -> "png";
			case "gif" -> throw new FileStorageValidationException("GIF thumbnails are not supported.");
			default -> throw new FileStorageValidationException("Only JPEG and PNG thumbnails are supported.");
		};
	}

	private BufferedImage readImage(Path sourcePath) {
		try (InputStream inputStream = Files.newInputStream(sourcePath)) {
			try {
				BufferedImage image = ImageIO.read(inputStream);
				if (image == null) {
					throw new ThumbnailGenerationException("Stored image could not be decoded.");
				}
				return image;
			} catch (IOException exception) {
				throw new ThumbnailGenerationException("Failed to decode stored image.", exception);
			}
		} catch (ThumbnailGenerationException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new FileStorageException("Failed to read stored image.", exception);
		}
	}

	private BufferedImage resize(BufferedImage source, String format) {
		double scale = Math.min(
				1.0,
				Math.min((double) MAX_DIMENSION / source.getWidth(), (double) MAX_DIMENSION / source.getHeight())
		);
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		int imageType = format.equals("png") && source.getColorModel().hasAlpha()
				? BufferedImage.TYPE_INT_ARGB
				: BufferedImage.TYPE_INT_RGB;
		BufferedImage resized = new BufferedImage(width, height, imageType);
		Graphics2D graphics = resized.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		return resized;
	}

	private void writeImage(BufferedImage image, String format, Path targetPath) {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
		if (!writers.hasNext()) {
			throw new ThumbnailGenerationException("No image encoder is available for the thumbnail format.");
		}

		ImageWriter writer = writers.next();
		try {
			OutputStream fileOutputStream;
			try {
				fileOutputStream = Files.newOutputStream(targetPath);
			} catch (IOException exception) {
				throw new FileStorageException("Failed to open temporary thumbnail file.", exception);
			}

			try (OutputStream outputStream = fileOutputStream;
				 ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
				if (imageOutputStream == null) {
					throw new ThumbnailGenerationException("Failed to create thumbnail encoder output.");
				}
				writer.setOutput(imageOutputStream);
				ImageWriteParam writeParam = writer.getDefaultWriteParam();
				if (format.equals("jpeg") && writeParam.canWriteCompressed()) {
					writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
					writeParam.setCompressionQuality(JPEG_QUALITY);
				}
				writer.write(null, new IIOImage(image, null, null), writeParam);
				imageOutputStream.flush();
			} catch (ThumbnailGenerationException | FileStorageException exception) {
				throw exception;
			} catch (IOException exception) {
				throw new ThumbnailGenerationException("Failed to encode thumbnail image.", exception);
			}
		} finally {
			writer.dispose();
		}
	}

	private void deleteTemporaryFile(Path temporaryFile) {
		if (temporaryFile == null) {
			return;
		}
		try {
			Files.deleteIfExists(temporaryFile);
		} catch (IOException ignored) {
			// Preserve the original thumbnail failure, matching FileStorageService cleanup behavior.
		}
	}
}
