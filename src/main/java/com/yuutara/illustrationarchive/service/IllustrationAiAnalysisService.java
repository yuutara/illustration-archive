package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AiAnalysisResponse;
import com.yuutara.illustrationarchive.dto.AiAnalysisResponse.Media;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AssetRepository.AnalysisSource;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.storage.FileStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;

@Service
public class IllustrationAiAnalysisService {
	// Reject large originals instead of adding a more elaborate decoder/pipeline to this experiment.
	static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
	static final long MAX_PIXELS = 16_000_000;
	static final int MAX_JPEG_BYTES = 3 * 1024 * 1024;
	private final IllustrationRepository illustrations;
	private final AssetRepository assets;
	private final FileStorage storage;
	private final GeminiClient client;

	public IllustrationAiAnalysisService(IllustrationRepository illustrations, AssetRepository assets,
			FileStorage storage, GeminiClient client) {
		this.illustrations = illustrations;
		this.assets = assets;
		this.storage = storage;
		this.client = client;
	}

	// No database transaction spans file reads or the external HTTP request. No archive writes.
	public AiAnalysisResponse analyze(long id) {
		if (illustrations.findDetailBaseById(id).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "插画不存在。");
		}
		client.checkAvailable();
		var sources = assets.findAnalysisSources(id);
		var staticSources = sources.stream().filter(source -> "image/jpeg".equals(source.mimeType())
				|| "image/png".equals(source.mimeType())).toList();
		if (staticSources.isEmpty() || staticSources.size() > 4) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					staticSources.isEmpty() ? "这件作品没有可分析的静态图片。" : "这件作品的静态图片较多，暂时无法一次分析。可继续查看原图。");
		}
		var analyzed = new ArrayList<Media>();
		var skipped = new ArrayList<Media>();
		var images = new ArrayList<String>();
		for (int i = 0; i < sources.size(); i++) {
			var source = sources.get(i);
			var media = new Media(source.id(), i + 1, source.mimeType());
			if ("image/jpeg".equals(source.mimeType()) || "image/png".equals(source.mimeType())) {
				images.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(prepareImage(source)));
				analyzed.add(media);
			} else {
				skipped.add(media);
			}
		}
		return client.analyze(images, analyzed, skipped);
	}

	byte[] prepareImage(AnalysisSource source) {
		try {
			if (source.fileSize() <= 0 || source.fileSize() > MAX_SOURCE_BYTES) throw new IllegalArgumentException();
			byte[] bytes;
			try (var input = storage.load(source.storageKey()).getInputStream()) {
				bytes = input.readNBytes(MAX_SOURCE_BYTES + 1);
			}
			if (bytes.length > MAX_SOURCE_BYTES) throw new IllegalArgumentException();
			// Explicit memory cache streams avoid ImageIO's default disk cache.
			try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
				var readers = ImageIO.getImageReaders(input);
				if (!readers.hasNext()) throw new IllegalArgumentException();
				var reader = readers.next();
				try {
					reader.setInput(input);
					String format = reader.getFormatName();
					if (!("JPEG".equalsIgnoreCase(format) && "image/jpeg".equals(source.mimeType()))
							&& !("PNG".equalsIgnoreCase(format) && "image/png".equals(source.mimeType()))) {
						throw new IllegalArgumentException();
					}
					int width = reader.getWidth(0), height = reader.getHeight(0);
					if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) throw new IllegalArgumentException();
					BufferedImage original = reader.read(0);
					double scale = Math.min(1.0, 2048.0 / Math.max(width, height));
					var resized = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
							Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_RGB);
					var graphics = resized.createGraphics();
					try {
						graphics.setColor(Color.WHITE);
						graphics.fillRect(0, 0, resized.getWidth(), resized.getHeight());
						graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
						graphics.drawImage(original, 0, 0, resized.getWidth(), resized.getHeight(), null);
					} finally {
						graphics.dispose();
						original.flush();
					}
					var output = new ByteArrayOutputStream();
					try (var encoded = new MemoryCacheImageOutputStream(output)) {
						var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
						try {
							writer.setOutput(encoded);
							var parameters = writer.getDefaultWriteParam();
							parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
							parameters.setCompressionQuality(0.88f);
							writer.write(null, new javax.imageio.IIOImage(resized, null, null), parameters);
							encoded.flush();
						} finally {
							writer.dispose();
							resized.flush();
						}
					}
					if (output.size() > MAX_JPEG_BYTES) throw new IllegalArgumentException();
					return output.toByteArray();
				} finally {
					reader.dispose();
				}
			}
		} catch (Exception exception) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"这张图片暂时无法分析。可继续查看原图，或重新导入较小的 JPEG / PNG 版本。");
		}
	}
}
