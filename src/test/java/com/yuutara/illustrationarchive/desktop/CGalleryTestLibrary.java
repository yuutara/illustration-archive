package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.service.AuthorService;
import com.yuutara.illustrationarchive.service.IllustrationImportResult;
import com.yuutara.illustrationarchive.service.IllustrationImportService;
import com.yuutara.illustrationarchive.service.IllustrationUpdateService;
import com.yuutara.illustrationarchive.service.TagService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds an isolated browse-test library inside a desktop PoC home (test code only).
 *
 * <p>Images go through the normal import service, so stored files, thumbnails, SHA-256 and rows are real.
 * Some works are then grouped into multi-page works by moving their assets under one illustration with a page
 * order, the same shape X imports produce (X import itself is disabled in desktop mode). One JPEG has its
 * thumbnail file removed so the page's thumbnail fallback can be checked; GIFs never have thumbnails.
 * Works get titles, three authors and overlapping tags for search and filter checks, plus filler authors and tags
 * so the candidate lists run past one page.
 */
final class CGalleryTestLibrary {

	/**
	 * Authors and tags without works, added beside the 3 real authors and 5 real tags: 43 authors and 45 tags in all,
	 * more than one page of the C gallery picker (30) and of the API default (20).
	 */
	static final int FILLER_FACETS = 40;

	/** Indexes of the generated images that become one multi-page work, in page order. */
	private static final int[][] GROUPS = { { 2, 3 }, { 8, 9, 10 }, { 15, 16, 17, 18 }, { 30, 31 }, { 44, 45, 46 } };
	private static final int[][] SIZES = { { 900, 1200 }, { 1200, 750 }, { 720, 1280 }, { 1000, 1000 } };
	private static final Color[][] PALETTES = {
			{ new Color(0xF2D7C4), new Color(0xD9775A), new Color(0x7C3E2E), new Color(0xFFF4E6) },
			{ new Color(0xC9DCE6), new Color(0x5C8DA8), new Color(0x22394D), new Color(0xF4F7F9) },
			{ new Color(0xE3E6CF), new Color(0x8FA36B), new Color(0x3D4F2B), new Color(0xFAF6E8) },
			{ new Color(0x2B2A45), new Color(0x7A5C99), new Color(0x14132B), new Color(0xF5D68C) },
			{ new Color(0xF6E3D8), new Color(0xE6A3A0), new Color(0x6E3B47), new Color(0xFFFFFF) },
			{ new Color(0xF0EDE6), new Color(0x2E2E2E), new Color(0xB33A2E), new Color(0xD9C9A3) }
	};

	record Built(int works, int images, List<Long> multiPageIds, long missingThumbnailAssetId, long gifAssetId) {
	}

	private CGalleryTestLibrary() {
	}

	static Built build(DesktopServer.Running running, int images) {
		var context = running.context();
		var importService = context.getBean(IllustrationImportService.class);
		var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
		var transactions = new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
		Path media = Path.of(context.getEnvironment().getRequiredProperty("illustration-archive.storage.root-dir"));

		List<IllustrationImportResult> imported = new ArrayList<>();
		List<String> formats = new ArrayList<>();
		for (int i = 0; i < images; i++) {
			String format = formatOf(i);
			formats.add(format);
			String name = "c-test-%02d.%s".formatted(i + 1, format.equals("jpeg") ? "jpg" : format);
			byte[] bytes = image(i, format);
			imported.add(importService.importSingle(new MockMultipartFile("file", name, "image/" + format, bytes)));
		}

		List<Long> multiPageIds = new ArrayList<>();
		for (int[] group : GROUPS) {
			if (group[group.length - 1] >= images) {
				continue;
			}
			long target = imported.get(group[0]).illustrationId();
			transactions.executeWithoutResult(status -> {
				for (int page = 0; page < group.length; page++) {
					IllustrationImportResult result = imported.get(group[page]);
					jdbc.update("UPDATE asset SET illustration_id = ?, sort_order = ? WHERE id = ?",
							target, page, result.assetId());
					if (result.illustrationId() != target) {
						jdbc.update("DELETE FROM illustration WHERE id = ?", result.illustrationId());
					}
				}
			});
			multiPageIds.add(target);
		}

		describe(context, jdbc, multiPageIds);

		// One single-page JPEG without its thumbnail file: the page must fall back to the original.
		long missingThumbnailAssetId = -1;
		for (int i = images - 1; i >= 0 && missingThumbnailAssetId < 0; i--) {
			if (formats.get(i).equals("jpeg") && !inGroup(i)) {
				long assetId = imported.get(i).assetId();
				String storageKey = jdbc.queryForObject("SELECT storage_key FROM asset WHERE id = ?", String.class, assetId);
				try {
					Files.deleteIfExists(media.resolve("thumbnails").resolve(storageKey));
				} catch (IOException exception) {
					throw new UncheckedIOException(exception);
				}
				missingThumbnailAssetId = assetId;
			}
		}
		long gifAssetId = -1;
		for (int i = 0; i < images && gifAssetId < 0; i++) {
			if (formats.get(i).equals("gif")) {
				gifAssetId = imported.get(i).assetId();
			}
		}
		int works = jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Integer.class);
		return new Built(works, images, multiPageIds, missingThumbnailAssetId, gifAssetId);
	}

	private static void describe(org.springframework.context.ApplicationContext context, JdbcTemplate jdbc, List<Long> multiPageIds) {
		var authors = context.getBean(AuthorService.class);
		var tags = context.getBean(TagService.class);
		var updates = context.getBean(IllustrationUpdateService.class);
		long amamiya = authors.create("雨宫ゆき", "amamiya_yuki").id();
		long lumen = authors.create("Lumen Kiln", "lumenkiln").id();
		long chiaki = authors.create("千秋", null).id();
		long sea = tags.create("海").id();
		long warm = tags.create("暖色").id();
		long series = tags.create("系列").id();
		long night = tags.create("夜空").id();
		long sky = tags.create("Blue Sky 天空").id();
		// More authors and tags than one candidate page; "Zz …" sorts before the CJK names, which the pickers then
		// reach only by paging or searching. These have no works.
		for (int n = 1; n <= FILLER_FACETS; n++) {
			authors.create("Zz 画师 %02d".formatted(n), null);
			tags.create("Zz 主题 %02d".formatted(n));
		}

		List<Long> ids = jdbc.queryForList("SELECT id FROM illustration ORDER BY id", Long.class);
		String[] titles = { "海边的第七个夏天", "Moon over Kiln", "晨雾里的山脊", "Harbor Lights", "紫阳花之庭", "退潮", "Form & Void", "星图" };
		long[] authorCycle = { amamiya, lumen, chiaki };
		for (int i = 0; i < ids.size(); i++) {
			if (i % 4 == 3) {
				continue; // leave some works untitled and without an author
			}
			long id = ids.get(i);
			IllustrationPatchRequest patch = new IllustrationPatchRequest();
			patch.setTitle(titles[i % titles.length] + (i >= titles.length ? " " + (i / titles.length + 1) : ""));
			patch.setAuthorId(authorCycle[i % authorCycle.length]);
			List<Long> tagIds = new ArrayList<>(List.of(i % 2 == 0 ? sea : night, i % 3 == 0 ? warm : sky));
			if (multiPageIds.contains(id)) {
				tagIds.add(series);
				patch.setNote("连作，共 " + jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE illustration_id = ?",
						Integer.class, id) + " 页。");
				patch.setSourceUrl("https://example.com/works/" + id);
			}
			patch.setTagIds(tagIds);
			updates.updateBasicMetadata(id, patch);
		}
	}

	private static boolean inGroup(int index) {
		for (int[] group : GROUPS) {
			for (int member : group) {
				if (member == index) {
					return true;
				}
			}
		}
		return false;
	}

	private static String formatOf(int index) {
		if (index % 9 == 5) {
			return "gif";
		}
		return index % 3 == 0 ? "png" : "jpeg";
	}

	/** A small landscape: sky gradient, sun, three ridges and a page number, distinct per index. */
	private static byte[] image(int index, String format) {
		int groupStart = index;
		for (int[] group : GROUPS) {
			for (int member : group) {
				if (member == index) {
					groupStart = group[0];
				}
			}
		}
		Color[] palette = PALETTES[groupStart % PALETTES.length];
		int[] size = SIZES[groupStart % SIZES.length];
		int w = size[0], h = size[1];
		Random random = new Random(index * 7919L + 17);
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(new GradientPaint(0, 0, palette[3], 0, h * 0.7f, palette[0]));
		g.fillRect(0, 0, w, h);
		g.setColor(palette[1]);
		int sun = (int) (Math.min(w, h) * (0.12 + random.nextDouble() * 0.08));
		g.fillOval((int) (w * (0.2 + random.nextDouble() * 0.6)) - sun, (int) (h * (0.18 + random.nextDouble() * 0.2)) - sun, sun * 2, sun * 2);
		for (int layer = 0; layer < 3; layer++) {
			Path2D ridge = new Path2D.Double();
			double base = h * (0.55 + layer * 0.13);
			ridge.moveTo(0, h);
			for (int x = 0; x <= w; x += Math.max(8, w / 40)) {
				ridge.lineTo(x, base - Math.sin(x / (w / (2.0 + layer + random.nextDouble()))) * h * 0.06 - random.nextDouble() * h * 0.02);
			}
			ridge.lineTo(w, h);
			ridge.closePath();
			Color c = layer == 2 ? palette[2] : blend(palette[1], palette[2], 0.35 + layer * 0.3);
			g.setColor(c);
			g.fill(ridge);
		}
		g.setColor(new Color(255, 255, 255, 170));
		g.setStroke(new BasicStroke(Math.max(2, w / 300f)));
		g.setFont(g.getFont().deriveFont((float) Math.max(28, w / 18)));
		g.drawString("#" + (index + 1), w / 20, h - h / 20);
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, format, out)) {
				throw new IllegalStateException("No ImageIO writer for " + format);
			}
			return out.toByteArray();
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private static Color blend(Color a, Color b, double t) {
		return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
				(int) (a.getBlue() * (1 - t) + b.getBlue() * t));
	}
}
