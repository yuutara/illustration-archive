package com.yuutara.illustrationarchive.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/** Generates thumbnails using temporary files; originals and final thumbnails reside in S3. */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3ThumbnailStorage implements ThumbnailStorage {
	private static final int MAX_DIMENSION = 600;
	private final S3ObjectStore objects;

	S3ThumbnailStorage(S3ObjectStore objects) {
		this.objects = objects;
	}

	@Override
	public String generateThumbnail(String storageKey) {
		S3ObjectStore.validateKey(storageKey);
		String format = imageFormat(storageKey);
		String thumbnailKey = thumbnailKey(storageKey);
		if (objects.exists(thumbnailKey)) return thumbnailKey;
		Path sourceFile = null;
		Path thumbnailFile = null;
		try {
			sourceFile = Files.createTempFile("archive-s3-original-", ".tmp");
			try (InputStream input = objects.load(storageKey).getInputStream()) {
				Files.copy(input, sourceFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			BufferedImage original;
			try (InputStream input = Files.newInputStream(sourceFile)) {
				original = ImageIO.read(new MemoryCacheImageInputStream(input));
			}
			if (original == null) throw new ThumbnailGenerationException("Stored image could not be decoded.");
			Path uploadFile = sourceFile;
			if (original.getWidth() > MAX_DIMENSION || original.getHeight() > MAX_DIMENSION) {
				thumbnailFile = Files.createTempFile("archive-s3-thumbnail-", ".tmp");
				writeImage(resize(original, format), format, thumbnailFile);
				uploadFile = thumbnailFile;
			}
			objects.put(thumbnailKey, uploadFile, format.equals("jpeg") ? "image/jpeg" : "image/png");
			return thumbnailKey;
		} catch (IOException exception) {
			throw new FileStorageException("Failed to generate S3 thumbnail.", exception);
		} finally {
			deleteTemporaryFile(thumbnailFile);
			deleteTemporaryFile(sourceFile);
		}
	}

	@Override
	public Resource loadThumbnail(String storageKey) {
		S3ObjectStore.validateKey(storageKey);
		String key = thumbnailKey(storageKey);
		if (!objects.exists(key)) throw new ThumbnailNotFoundException();
		return objects.load(key);
	}

	@Override
	public void deleteThumbnail(String storageKey) {
		S3ObjectStore.validateKey(storageKey);
		objects.delete(thumbnailKey(storageKey));
	}

	private static String thumbnailKey(String storageKey) {
		return "thumbnails/" + storageKey;
	}

	private static String imageFormat(String key) {
		String extension = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
		return switch (extension) {
			case "jpg", "jpeg" -> "jpeg";
			case "png" -> "png";
			case "gif" -> throw new FileStorageValidationException("GIF thumbnails are not supported.");
			default -> throw new FileStorageValidationException("Only JPEG and PNG thumbnails are supported.");
		};
	}

	private static BufferedImage resize(BufferedImage source, String format) {
		double scale = Math.min(1.0, Math.min((double) MAX_DIMENSION / source.getWidth(),
				(double) MAX_DIMENSION / source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		int imageType = format.equals("png") && source.getColorModel().hasAlpha()
				? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
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

	private static void writeImage(BufferedImage image, String format, Path target) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
		if (!writers.hasNext()) throw new ThumbnailGenerationException("No image encoder is available for the thumbnail format.");
		ImageWriter writer = writers.next();
		try (var output = Files.newOutputStream(target);
				var imageOutput = new MemoryCacheImageOutputStream(output)) {
			writer.setOutput(imageOutput);
			ImageWriteParam params = writer.getDefaultWriteParam();
			if (format.equals("jpeg") && params.canWriteCompressed()) {
				params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				params.setCompressionQuality(0.85f);
			}
			writer.write(null, new IIOImage(image, null, null), params);
			imageOutput.flush();
		} finally {
			writer.dispose();
		}
	}

	private static void deleteTemporaryFile(Path file) {
		if (file == null) return;
		try { Files.deleteIfExists(file); } catch (IOException ignored) { }
	}
}
