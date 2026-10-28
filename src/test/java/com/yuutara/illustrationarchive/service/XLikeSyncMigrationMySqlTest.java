package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.*;
import com.yuutara.illustrationarchive.repository.*;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in migration/transaction acceptance; only a fresh, strictly named disposable local schema. */
@EnabledIfEnvironmentVariable(named = "X_SYNC_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class XLikeSyncMigrationMySqlTest {
	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class Transactions { }

	@Test
	void upgradePreserves351PendingStatusesMediaCursorAndTestsOrderingRollbackAndArchiveDedup() {
		String url = System.getenv("X_SYNC_MYSQL_TEST_URL");
		var match = java.util.regex.Pattern.compile("^jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/"
				+ "(illustration_archive_x_sync_test_[0-9]+)(?:\\?.*)?$").matcher(url);
		assertTrue(match.matches(), "Only a fresh dedicated local X Sync test schema is allowed.");
		String schema = match.group(1);
		String user = System.getenv("X_SYNC_MYSQL_TEST_USERNAME"), password = System.getenv("X_SYNC_MYSQL_TEST_PASSWORD");
		var server = new DriverManagerDataSource(url.replace("/" + schema, "/"), user, password);
		var admin = new JdbcTemplate(server);
		// No IF NOT EXISTS: a pre-existing schema is never reused or removed by this test.
		admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		try {
			var dataSource = new DriverManagerDataSource(url, user, password);
			Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("6").load().migrate();
			var jdbc = new JdbcTemplate(dataSource);
			jdbc.update("INSERT INTO illustration(id,source_url) VALUES (1,'https://example.test/status/imported')");
			jdbc.update("INSERT INTO asset(illustration_id,original_filename,storage_key,mime_type,file_size,sort_order,sha256)"
					+ " VALUES (1,'old.jpg','fixture/old.jpg','image/jpeg',1,0,?)", "a".repeat(64));
			for (int id = 1; id <= 354; id++) {
				String status = id <= 351 ? "PENDING" : id == 352 ? "IMPORTED" : id == 353 ? "SKIPPED" : "UNSUPPORTED";
				jdbc.update("INSERT INTO x_like_item(id,x_post_id,x_author_id,author_username,author_display_name,status,"
						+ "post_created_at,discovered_at,updated_at,imported_illustration_id) VALUES (?,?, '7','fixture','Fixture',?,?,?,?,?)",
						id, "legacy-" + id, status, LocalDateTime.of(2020, 1, 1, 0, 0),
						LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id), LocalDateTime.of(2026, 1, 1, 0, 0), id == 352 ? 1L : null);
				jdbc.update("INSERT INTO x_like_media(x_like_item_id,media_key,sort_order,media_type,source_url)"
						+ " VALUES (?,'old',0,'photo','https://example.test/old.jpg')", id);
			}
			jdbc.update("INSERT INTO x_like_sync_state VALUES (1,'fixture-old-cursor',5,'ACTIVE',UTC_TIMESTAMP(3))");
			jdbc.update("INSERT INTO x_like_sync_seen_token VALUES (UNHEX(SHA2('fixture-old-cursor',256)))");
			String oldColumns = "id,x_post_id,x_author_id,author_username,author_display_name,post_text,post_created_at,"
					+ "status,discovered_at,updated_at,imported_illustration_id";
			var beforeItems = jdbc.queryForList("SELECT " + oldColumns + " FROM x_like_item ORDER BY id");
			var beforeMedia = jdbc.queryForList("SELECT * FROM x_like_media ORDER BY id");
			var beforeState = jdbc.queryForList("SELECT id,next_token,max_results,status,updated_at FROM x_like_sync_state");
			var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
			assertEquals(1, flyway.migrate().migrationsExecuted);
			assertEquals(beforeItems, jdbc.queryForList("SELECT " + oldColumns + " FROM x_like_item ORDER BY id"));
			assertEquals(beforeMedia, jdbc.queryForList("SELECT * FROM x_like_media ORDER BY id"));
			assertEquals(beforeState, jdbc.queryForList("SELECT id,next_token,max_results,status,updated_at FROM x_like_sync_state"));
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_seen_token WHERE sync_id=1"
					+ " AND token_sha256=UNHEX(SHA2('fixture-old-cursor',256))", Integer.class));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_state WHERE id=2", Integer.class));
			assertEquals(354, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item WHERE likes_order=0", Integer.class));
			assertEquals(351, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item WHERE status='PENDING'", Integer.class));
			assertEquals(List.of("status", "likes_order", "discovered_at", "id"), jdbc.queryForList(
					"SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()"
							+ " AND TABLE_NAME='x_like_item' AND INDEX_NAME='idx_x_like_inbox' ORDER BY SEQ_IN_INDEX", String.class));
			assertEquals(0, flyway.migrate().migrationsExecuted);

			var items = new XLikeRepository(jdbc);
			var fail = new AtomicBoolean();
			var checkpointFail = new AtomicBoolean();
			var states = new XLikeSyncStateRepository(jdbc) {
				@Override public void saveRecent(RecentState state) {
					super.saveRecent(state);
					if (checkpointFail.get()) throw new IllegalStateException("fixture checkpoint failure");
				}
			};
			var media = new XLikeMediaRepository(jdbc) {
				@Override public void insert(long id, XLikeMedia attachment) {
					super.insert(id, attachment);
					if (fail.get() && "fail".equals(attachment.mediaKey())) throw new IllegalStateException("fixture media failure");
				}
			};
			try (var context = new AnnotationConfigApplicationContext()) {
				context.register(Transactions.class);
				context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
				context.registerBean(XLikeRepository.class, () -> items);
				context.registerBean(XLikeMediaRepository.class, () -> media);
				context.registerBean(XLikePersistenceService.class);
				context.registerBean(XLikeSyncStateRepository.class, () -> states);
				context.refresh();
				var persistence = context.getBean(XLikePersistenceService.class);
				assertEquals("legacy-351", items.findPendingPage(24, 0).get(0).xPostId());
				var api = List.of(candidate("9000", "photo", "2030-01-01T00:00:00Z"),
						candidate("2", "photo", "2010-01-01T00:00:00Z"), candidate("700", "photo", "2040-01-01T00:00:00Z"));
				persistence.saveOrderedPage(new XLikePage(api, true, "fixture-next"), 100, false);
				jdbc.update("UPDATE x_like_item SET discovered_at='2026-10-14 00:00:00.000' WHERE likes_order>0");
				assertEquals(List.of("9000", "2", "700"), items.findPendingPage(3, 0).stream().map(XLikeInboxItem::xPostId).toList());
				persistence.saveOrderedPage(new XLikePage(List.of(candidate("history-1", "photo", "2050-01-01T00:00:00Z"),
						candidate("history-2", "photo", "2000-01-01T00:00:00Z")), false, null), -1, true);
				var all = items.findPendingPage(1000, 0).stream().map(XLikeInboxItem::xPostId).toList();
				assertEquals(List.of("history-1", "history-2"), all.subList(all.size() - 2, all.size()));
				persistence.saveOrderedPage(new XLikePage(List.of(api.get(0), api.get(1)), false, null), 200, false);
				persistence.saveOrderedPage(new XLikePage(List.of(api.get(0)), false, null), 190, false);
				assertEquals(200L, jdbc.queryForObject("SELECT likes_order FROM x_like_item WHERE x_post_id='9000'", Long.class));
				var processed = persistence.saveOrderedPage(new XLikePage(List.of(
						candidate("legacy-352", "photo", "2020-01-01T00:00:00Z"),
						candidate("legacy-353", "photo", "2020-01-01T00:00:00Z")), false, null), 300, false);
				assertEquals(2, processed.existingCount()); assertEquals(0, processed.pendingCount());
				assertEquals("IMPORTED", jdbc.queryForObject("SELECT status FROM x_like_item WHERE id=352", String.class));
				assertEquals(1L, jdbc.queryForObject("SELECT imported_illustration_id FROM x_like_item WHERE id=352", Long.class));
				assertEquals("SKIPPED", jdbc.queryForObject("SELECT status FROM x_like_item WHERE id=353", String.class));
				assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_media WHERE x_like_item_id=352", Integer.class));

				// A real Spring proxy rolls back a new item, its media and an earlier rank update together.
				fail.set(true);
				assertThrows(IllegalStateException.class, () -> persistence.saveOrderedPage(new XLikePage(List.of(api.get(0),
						candidate("failed-row", "fail", "2020-01-01T00:00:00Z")), true, "ignored"), 1000, false));
				assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item WHERE x_post_id='failed-row'", Integer.class));
				assertEquals(200L, jdbc.queryForObject("SELECT likes_order FROM x_like_item WHERE x_post_id='9000'", Long.class));
				fail.set(false);

				// Different Posts with the same source photo are Pending; SHA-256 rejects them only at Archive.
				var post = items.findForImport(jdbc.queryForObject("SELECT id FROM x_like_item WHERE x_post_id='2'", Long.class), false).orElseThrow();
				var archive = new XPostPersistenceService(items, new AuthorRepository(jdbc), GalleryMapperSupport.repository(jdbc), new AssetRepository(jdbc));
				assertThrows(XPostDuplicateException.class, () -> archive.persist(post, media.findByItemId(post.id()),
						List.of(new StoredFile("same.jpg", "fixture/same.jpg", "image/jpeg", 1, "a".repeat(64)))));
				assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
				assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM asset", Integer.class));
				assertEquals(XLikeStatus.PENDING, items.findStatusByPostId("2"));
				var state = context.getBean(XLikeSyncStateRepository.class);
				var checkpoint = new XLikeSyncStateRepository.RecentState("fixture-old-cursor", 25, "ACTIVE",
						List.of("anchor-1", "anchor-2"), List.of("head-1", "head-2"), 1, 10000, 300, false);
				state.restartRecent(checkpoint);
				assertEquals(checkpoint, new XLikeSyncStateRepository(jdbc).loadRecent().orElseThrow());
				assertTrue(state.hasSeen("fixture-old-cursor"));
				assertTrue(state.recentHasSeen("fixture-old-cursor")); // Identical opaque tokens, independent scopes.
				var next = new XLikeSyncStateRepository.RecentState("recent-next", 25, "ACTIVE",
						checkpoint.anchorIds(), checkpoint.headIds(), 0, 9998, 300, false);
				var checkpointPage = new XLikePage(List.of(api.get(0),
						candidate("checkpoint-row", "photo", "2020-01-01T00:00:00Z")), true, "recent-next");
				checkpointFail.set(true);
				assertThrows(IllegalStateException.class, () -> persistence.saveRecentPage(checkpointPage, checkpoint, next, Set.of()));
				assertEquals(checkpoint, state.loadRecent().orElseThrow());
				assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item WHERE x_post_id='checkpoint-row'", Integer.class));
				assertEquals(200L, jdbc.queryForObject("SELECT likes_order FROM x_like_item WHERE x_post_id='9000'", Long.class));
				assertFalse(state.recentHasSeen("recent-next"));
				checkpointFail.set(false);
				persistence.saveRecentPage(checkpointPage, checkpoint, next, Set.of());
				assertEquals(next, state.loadRecent().orElseThrow());
				assertEquals(10000L, jdbc.queryForObject("SELECT likes_order FROM x_like_item WHERE x_post_id='9000'", Long.class));
				assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_media m JOIN x_like_item i ON m.x_like_item_id=i.id"
						+ " WHERE i.x_post_id='checkpoint-row'", Integer.class));
				assertTrue(state.recentHasSeen("recent-next"));
				state.markComplete(5);
				assertEquals("COMPLETE", state.load().orElseThrow().status());
				assertNull(state.load().orElseThrow().nextToken());
				assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM x_like_sync_seen_token WHERE sync_id=1", Integer.class));
				assertEquals(next, state.loadRecent().orElseThrow());
				assertTrue(state.recentHasSeen("recent-next"));
				state.clear();
				assertTrue(state.load().isEmpty());
				assertEquals(next, state.loadRecent().orElseThrow());
				assertTrue(state.recentHasSeen("recent-next"));
			}
		} finally {
			admin.execute("DROP DATABASE `" + schema + "`");
		}
	}

	private static XLikeCandidate candidate(String id, String key, String created) {
		return new XLikeCandidate(id, "7", "fixture", "Fixture", null, Instant.parse(created), XLikeStatus.PENDING,
				List.of(new XLikeMedia(key, 0, "photo", "https://example.test/same.jpg", 10, 10)));
	}
}
