package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.*;
import com.yuutara.illustrationarchive.repository.XLikeMediaRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only; creates a fresh dedicated schema and removes only that schema after verification. */
@EnabledIfEnvironmentVariable(named = "F06_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class XAnimatedPersistenceMySqlTest {
	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class Transactions { }

	@Test
	void mediaReplacementAndStateRecoveryRollBackTogetherThroughSpringProxy() {
		String url = System.getenv("F06_MYSQL_TEST_URL");
		var match = java.util.regex.Pattern.compile("^jdbc:mysql://[^/]+/(illustration_archive_f06_test_[0-9]+)(?:\\?.*)?$").matcher(url);
		assertTrue(match.matches(), "A fresh dedicated F06 schema is required.");
		String schema = match.group(1);
		var server = new DriverManagerDataSource(url.replace("/" + schema, "/"),
				System.getenv("F06_MYSQL_TEST_USERNAME"), System.getenv("F06_MYSQL_TEST_PASSWORD"));
		var admin = new JdbcTemplate(server);
		admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		try {
			var dataSource = new DriverManagerDataSource(url, System.getenv("F06_MYSQL_TEST_USERNAME"), System.getenv("F06_MYSQL_TEST_PASSWORD"));
			Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
			var jdbc = new JdbcTemplate(dataSource);
			var items = new XLikeRepository(jdbc);
			var fail = new AtomicBoolean();
			var media = new XLikeMediaRepository(jdbc) {
				@Override public void insert(long id, XLikeMedia attachment) {
					assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
					super.insert(id, attachment);
					if (fail.get()) throw new IllegalStateException("After media insert");
				}
			};
			try (var context = new AnnotationConfigApplicationContext()) {
				context.register(Transactions.class);
				context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
				context.registerBean(XLikeRepository.class, () -> items);
				context.registerBean(XLikeMediaRepository.class, () -> media);
				context.registerBean(XLikePersistenceService.class);
				context.refresh();
				var service = context.getBean(XLikePersistenceService.class);
				var old = new XLikeMedia("g", 0, "animated_gif", null, 100, 100);
				var animated = new XLikeMedia("g", 0, "animated_gif", "https://video.twimg.com/a.mp4", 320, 240);
				service.savePage(page("1", XLikeStatus.UNSUPPORTED, old));
				long id = jdbc.queryForObject("SELECT id FROM x_like_item WHERE x_post_id = '1'", Long.class);
				fail.set(true);
				assertThrows(IllegalStateException.class, () -> service.savePage(page("1", XLikeStatus.PENDING, animated)));
				assertEquals(XLikeStatus.UNSUPPORTED, items.findStatusByPostId("1"));
				assertEquals(List.of(old), media.findByItemId(id));
				fail.set(false);
				assertEquals(1, service.savePage(page("1", XLikeStatus.PENDING, animated)).pendingCount());
				assertEquals(List.of(animated), media.findByItemId(id));
				for (XLikeStatus status : List.of(XLikeStatus.SKIPPED, XLikeStatus.IMPORTED)) {
					jdbc.update("UPDATE x_like_item SET status = ? WHERE id = ?", status.name(), id);
					service.savePage(page("1", XLikeStatus.PENDING, old));
					assertEquals(status, items.findStatusByPostId("1"));
					assertEquals(List.of(animated), media.findByItemId(id));
				}
			}
		} finally { admin.execute("DROP DATABASE `" + schema + "`"); }
	}

	private XLikePage page(String id, XLikeStatus status, XLikeMedia attachment) {
		var candidate = new XLikeCandidate(id, "123", "artist", "Artist", null, null, status, List.of(attachment));
		return new XLikePage(List.of(candidate), false, null);
	}
}
