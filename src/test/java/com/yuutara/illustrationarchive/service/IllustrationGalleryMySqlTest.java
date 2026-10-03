package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

// Explicit opt-in only. A fresh, specially named schema is mandatory; no existing library is modified.
@EnabledIfEnvironmentVariable(named = "F03_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class IllustrationGalleryMySqlTest {

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class Transactions {}

	@Test
	void searchesAndPaginatesRealMySqlThroughTheSpringTransactionProxy() throws Exception {
		String url = System.getenv("F03_MYSQL_TEST_URL");
		var schema = java.util.regex.Pattern.compile("^jdbc:mysql://[^/]+/(illustration_archive_f03_test_[0-9]+)(?:\\?.*)?$").matcher(url);
		assertTrue(schema.matches(), "Only a dedicated F03 test schema is allowed.");
		String database = schema.group(1);
		var server = new DriverManagerDataSource(url.replace("/" + database, "/"),
				System.getenv("F03_MYSQL_TEST_USERNAME"), System.getenv("F03_MYSQL_TEST_PASSWORD"));
		new JdbcTemplate(server).execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		var dataSource = new DriverManagerDataSource(url, System.getenv("F03_MYSQL_TEST_USERNAME"), System.getenv("F03_MYSQL_TEST_PASSWORD"));
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		var jdbc = new JdbcTemplate(dataSource);
		seed(jdbc);
		try (var context = new AnnotationConfigApplicationContext()) {
			context.register(Transactions.class);
			context.registerBean(DataSource.class, () -> dataSource);
			context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
			context.registerBean(IllustrationRepository.class, () -> new IllustrationRepository(jdbc) {
				@Override public long count(IllustrationGalleryQuery query) {
					assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
					assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
					return super.count(query);
				}
			});
			context.registerBean(AuthorRepository.class, () -> new AuthorRepository(jdbc));
			context.registerBean(TagRepository.class, () -> new TagRepository(jdbc));
			context.registerBean(IllustrationGalleryService.class);
			context.refresh();
			var service = context.getBean(IllustrationGalleryService.class);
			assertPages(service, new IllustrationGalleryQuery(null, null, null), descending(1, 60));
			assertPages(service, new IllustrationGalleryQuery(null, 1L, null), descending(1, 36));
			assertPages(service, new IllustrationGalleryQuery(null, null, 1L), descending(1, 60).stream().filter(id -> id % 2 == 0).toList());
			assertPages(service, new IllustrationGalleryQuery("探索", null, null), descending(1, 60).stream().filter(id -> id % 2 == 0 || id % 3 == 0).toList());
			assertPages(service, new IllustrationGalleryQuery("探索", 1L, 1L), descending(1, 36).stream().filter(id -> id % 2 == 0).toList());
			assertPages(service, new IllustrationGalleryQuery("needle", null, null), List.of(1L));
			assertPages(service, new IllustrationGalleryQuery("@same_a", null, null), descending(1, 36));
			assertPages(service, new IllustrationGalleryQuery("Same artist", null, null), descending(1, 48));
			assertPages(service, new IllustrationGalleryQuery("100%_!\\'", null, null), List.of(60L, 2L));
			assertPages(service, new IllustrationGalleryQuery("不存在的关键词", null, null), List.of());
			var missing = service.getGallery(0, 24, new IllustrationGalleryQuery(null, 999L, 998L));
			assertEquals(0, missing.totalElements()); assertEquals(999L, missing.filters().authorId());
			assertNull(missing.filters().author()); assertNull(missing.filters().tag());
			var empty = service.getGallery(99, 24, new IllustrationGalleryQuery(null, 1L, null));
			assertEquals(36, empty.totalElements()); assertTrue(empty.items().isEmpty());
			var note = service.getGallery(0, 24, new IllustrationGalleryQuery("needle", null, null)).items().get(0);
			assertEquals(3, note.assetCount()); assertEquals(11L, note.coverAssetId());
			assertEquals(List.of(11L, 12L, 13L), note.assets().stream().map(asset -> asset.id()).toList());
			assertEquals(36, service.getGallery(0, 24, new IllustrationGalleryQuery("  ", 1L, null)).totalElements());
		}
		System.out.println("F03 real MySQL: 60 fixture works, duplicate tags, 3 pages, literal search and proxied read-only transaction passed. Schema retained for browser acceptance: " + database);
	}

	private List<Long> descending(long first, long last) {
		return LongStream.rangeClosed(first, last).boxed().sorted(java.util.Comparator.reverseOrder()).toList();
	}

	private void assertPages(IllustrationGalleryService service, IllustrationGalleryQuery query, List<Long> expected) {
		List<Long> actual = new ArrayList<>();
		int pages = (expected.size() + 23) / 24;
		for (int page = 0; page < Math.max(1, pages); page++) {
			IllustrationGalleryPage result = service.getGallery(page, 24, query);
			assertEquals(expected.size(), result.totalElements()); assertEquals(pages, result.totalPages());
			assertTrue(result.items().size() <= 24);
			actual.addAll(result.items().stream().map(item -> item.id()).toList());
			result.items().forEach(item -> assertEquals(item.assetCount(), item.assets().size()));
		}
		assertEquals(expected, actual); assertEquals(actual.size(), new HashSet<>(actual).size());
	}

	private void seed(JdbcTemplate jdbc) throws Exception {
		jdbc.update("INSERT INTO author(id, display_name, x_username) VALUES (1,'Same artist','same_a'),(2,'Same artist','same_b')");
		jdbc.update("INSERT INTO tag(id,name) VALUES (1,'探索风景'),(2,'探索旅行'),(3,?)", "100%_!\\'");
		String mediaRoot = System.getenv("F03_MYSQL_TEST_MEDIA_ROOT");
		Path root = mediaRoot == null ? null : Path.of(mediaRoot);
		if (root != null) Files.createDirectories(root.resolve("thumbnails"));
		for (int id = 1; id <= 60; id++) {
			Long authorId = id <= 36 ? Long.valueOf(1L) : id <= 48 ? Long.valueOf(2L) : null;
			jdbc.update("INSERT INTO illustration(id,title,author_id,note,created_at) VALUES(?,?,?,?, '2026-01-01 12:00:00')",
					id, id == 60 ? "100%_!\\' report" : "夏日 " + id, authorId, id == 1 ? "needle 备注" : null);
			if (id % 2 == 0) jdbc.update("INSERT INTO illustration_tag VALUES (?,1)", id);
			if (id % 3 == 0) jdbc.update("INSERT INTO illustration_tag VALUES (?,2)", id);
			if (id == 2) jdbc.update("INSERT INTO illustration_tag VALUES (?,3)", id);
			for (int order = 0; order < (id == 1 ? 3 : 1); order++) {
				long assetId = id * 10L + order + 1;
				String key = "fixture-" + assetId + ".png";
				if (root != null) {
					var image = new BufferedImage(320, id % 3 == 0 ? 480 : 240, BufferedImage.TYPE_INT_RGB);
					var graphics = image.createGraphics();
					graphics.setColor(new Color(170 + id % 60, 195 + id % 40, 180 + id % 50));
					graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
					graphics.setColor(new Color(70, 90, 70)); graphics.drawString("Work " + id + " / Asset " + (order + 1), 20, 40);
					graphics.dispose(); ImageIO.write(image, "png", root.resolve(key).toFile());
					// These small fixture images have the same thumbnail bytes, matching local storage's contract.
					ImageIO.write(image, "png", root.resolve("thumbnails").resolve(key).toFile());
				}
				jdbc.update("INSERT INTO asset(id,illustration_id,original_filename,storage_key,mime_type,file_size,sort_order) VALUES(?,?,?,?, 'image/png',?,?)",
						assetId, id, key, key, root == null ? 1 : Files.size(root.resolve(key)), order);
			}
		}
	}
}
