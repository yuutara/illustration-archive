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
            assertPages(service, IllustrationGalleryQuery.withIds(null, List.of(2L, 1L), List.of()), descending(1, 48));
            assertPages(service, IllustrationGalleryQuery.withIds(null, List.of(), List.of(2L, 1L)),
                    descending(1, 60).stream().filter(id -> id % 2 == 0 || id % 3 == 0).toList());
            assertPages(service, IllustrationGalleryQuery.withIds("探索", List.of(1L, 2L), List.of(1L, 2L)),
                    descending(1, 48).stream().filter(id -> id % 2 == 0 || id % 3 == 0).toList());
            assertPages(service, IllustrationGalleryQuery.withIds(null, List.of(1L, 999L), List.of(1L, 2L, 999L)),
                    descending(1, 36).stream().filter(id -> id % 2 == 0 || id % 3 == 0).toList());
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
		// Reuse this opt-in schema for metadata picker HTTP/browser acceptance.
		for (int id = 1; id <= 24; id++) {
			jdbc.update("INSERT INTO author(display_name,x_username) VALUES (?,?)", "Picker author %02d".formatted(id), "picker_%02d".formatted(id));
			jdbc.update("INSERT INTO tag(name) VALUES (?)", "Picker tag %02d".formatted(id));
		}
		var authors = new AuthorService(new AuthorRepository(jdbc));
		var tags = new TagService(new TagRepository(jdbc));
		assertEquals(20, authors.search(null, 20, 0).size());
		assertEquals(6, authors.search("  ", 20, 20).size());
		assertEquals(27, tags.search(null, 100, 0).size());
		assertEquals(7, tags.search("", 20, 20).size());
		var authorIds = new ArrayList<Long>();
		authorIds.addAll(authors.search(null, 20, 0).stream().map(a -> a.id()).toList());
		authorIds.addAll(authors.search(null, 20, 20).stream().map(a -> a.id()).toList());
		assertEquals(authors.search(null, 100, 0).stream().map(a -> a.id()).toList(), authorIds);
		assertEquals(26, new HashSet<>(authorIds).size());
		assertEquals(List.of(1L, 2L), authors.search("Same artist", 20, 0).stream().map(a -> a.id()).toList());
		assertEquals(List.of(2L), authors.search("@same_b", 20, 0).stream().map(a -> a.id()).toList());
		assertEquals(24, authors.search("picker_", 100, 0).size());
		assertEquals(24, tags.search("Picker tag", 100, 0).size());
		assertTrue(authors.search(null, 20, 100).isEmpty());
		assertTrue(tags.search("missing metadata", 20, 0).isEmpty());
        verifyIdentityClaims(jdbc, dataSource);
		System.out.println("F03 real MySQL: 60 fixture works, duplicate tags, 3 pages, literal search and proxied read-only transaction passed. Schema retained for browser acceptance: " + database);
	}

    private void verifyIdentityClaims(JdbcTemplate jdbc, DataSource dataSource) {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.executeWithoutResult(status -> {
            var authors = new AuthorRepository(jdbc);
            jdbc.update("INSERT INTO author(id,display_name,x_username) VALUES (200,'Old manual','@Reuse')");
            assertEquals(200L, authors.claimLegacyXAuthor("stable-200", "Current profile", "reuse").orElseThrow());
            assertEquals(200L, authors.findIdByXUserId("stable-200").orElseThrow());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM author WHERE x_user_id='stable-200'", Long.class));
            assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> authors.insertXAuthor("stable-200", "Name", "new-handle"));
            jdbc.update("INSERT INTO author(id,display_name,x_username) VALUES (201,'Same','ambiguous'),(202,'Same','@AMBIGUOUS')");
            assertTrue(authors.claimLegacyXAuthor("new-id", "Same", "ambiguous").isEmpty());
            jdbc.update("INSERT INTO author(id,display_name,x_username,x_user_id) VALUES (203,'Conflict','conflict','different-id')");
            assertTrue(authors.claimLegacyXAuthor("new-id", "Conflict", "conflict").isEmpty());
            jdbc.update("INSERT INTO author(id,display_name,x_username) VALUES (204,'Unproven','unproven')");
            jdbc.update("INSERT INTO illustration(id,author_id) VALUES (200,204)");
            assertTrue(authors.claimLegacyXAuthor("new-id", "Unproven", "unproven").isEmpty());
            jdbc.update("INSERT INTO author(id,display_name,x_username) VALUES (205,'Historic conflict','historic')");
            jdbc.update("INSERT INTO x_like_item(x_post_id,x_author_id,author_username,author_display_name,status,discovered_at,updated_at) VALUES ('identity-post-1','previous-owner','historic','Historic','PENDING',NOW(3),NOW(3))");
            assertTrue(authors.claimLegacyXAuthor("current-owner", "Historic", "historic").isEmpty());
            jdbc.update("INSERT INTO author(id,display_name,x_username) VALUES (207,'Proven manual','@proven')");
            jdbc.update("INSERT INTO illustration(id,author_id,source_url) VALUES (201,207,'https://x.com/proven/status/identity-post-2')");
            jdbc.update("INSERT INTO x_like_item(x_post_id,x_author_id,author_username,author_display_name,status,discovered_at,updated_at) VALUES ('identity-post-2','stable-207','proven','Current','PENDING',NOW(3),NOW(3))");
            assertEquals(207L, authors.claimLegacyXAuthor("stable-207", "Current", "proven").orElseThrow());
            status.setRollbackOnly();
        });
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM author WHERE id>=200", Long.class));
        assertEquals(60L, jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Long.class));
        // Exercise the real Spring proxy: claiming an author and inserting a Post roll back with an Asset failure.
        jdbc.update("INSERT INTO author(id,display_name,x_username) VALUES (206,'Rollback manual','rollback')");
        jdbc.update("INSERT INTO x_like_item(id,x_post_id,x_author_id,author_username,author_display_name,status,discovered_at,updated_at) VALUES (206,'identity-rollback-post','rollback-id','rollback','Updated','PENDING',NOW(3),NOW(3))");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(AuthorRepository.class, () -> new AuthorRepository(jdbc));
            var inbox = new com.yuutara.illustrationarchive.repository.XLikeRepository(jdbc);
            context.registerBean(com.yuutara.illustrationarchive.repository.XLikeRepository.class, () -> inbox);
            context.registerBean(IllustrationRepository.class, () -> new IllustrationRepository(jdbc));
            context.registerBean(com.yuutara.illustrationarchive.repository.AssetRepository.class, () -> new com.yuutara.illustrationarchive.repository.AssetRepository(jdbc) {
                @Override public long insert(long illustrationId, String filename, String key, String mime, long size, int order, String sha) {
                    super.insert(illustrationId, filename, key, mime, size, order, sha);
                    throw new IllegalStateException("simulated Asset failure after database insert");
                }
            });
            context.registerBean(XPostPersistenceService.class);
            context.refresh();
            assertThrows(IllegalStateException.class, () -> context.getBean(XPostPersistenceService.class).persist(
                    inbox.findForImport(206, false).orElseThrow(),
                    List.of(new com.yuutara.illustrationarchive.dto.XLikeMedia("identity-media", 0, "photo", "fixture", 1, 1)),
                    List.of(new com.yuutara.illustrationarchive.storage.StoredFile("fixture.png", "identity-rollback.png", "image/png", 1, "a".repeat(64)))));
        }
        assertNull(jdbc.queryForObject("SELECT x_user_id FROM author WHERE id=206", String.class));
        assertEquals("Rollback manual", jdbc.queryForObject("SELECT display_name FROM author WHERE id=206", String.class));
        assertEquals(60L, jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE storage_key='identity-rollback.png'", Long.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM x_like_item WHERE id=206", String.class));
        jdbc.update("DELETE FROM x_like_item WHERE id=206");
        jdbc.update("DELETE FROM author WHERE id=206");
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
