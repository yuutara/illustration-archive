package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Opt-in tool for browser acceptance of the C gallery, not part of the normal test run.
 *
 * <p>Runs only with {@code -Dc.acceptance.home=<dir>}, a directory outside the repository. It starts the desktop
 * server on that isolated H2 home (never the MySQL setup), builds the test library once when the home is empty
 * (skip with {@code -Dc.acceptance.seed=false} to check the empty gallery), writes the one-time session URL to
 * {@code <home>/acceptance-session.txt}, and keeps serving until {@code <home>/acceptance-stop} appears.
 * Running it again on the same home reads the existing library, which is how restart is checked.
 * {@code -Dc.acceptance.inbox=<n>} also fills the X Inbox with n made-up Posts whose images are local works,
 * so X 收藏 can be reviewed without any X account. Archiving them fails at the download step by design: the
 * downloader only accepts X's CDN, which makes this the way to see a failed archive.
 */
@EnabledIfSystemProperty(named = "c.acceptance.home", matches = ".+")
class CGalleryAcceptanceServer {

	@Test
	void serveIsolatedLibraryUntilStopped() throws Exception {
		Path home = Path.of(System.getProperty("c.acceptance.home")).toAbsolutePath().normalize();
		Files.createDirectories(home);
		Path stop = home.resolve("acceptance-stop");
		Files.deleteIfExists(stop);
		DesktopServer.Running running = DesktopServer.start(home, 0, true);
		try {
			JdbcTemplate jdbc = new JdbcTemplate(running.context().getBean(DataSource.class));
			int existing = jdbc.queryForObject("SELECT COUNT(*) FROM illustration", Integer.class);
			if (existing == 0 && !"false".equals(System.getProperty("c.acceptance.seed"))) {
				var built = CGalleryTestLibrary.build(running, Integer.getInteger("c.acceptance.images", 64));
				// Candidates that the picker (30 per page) shows only after scrolling to its second page, or by searching
				var authorsPast30 = running.context().getBean(com.yuutara.illustrationarchive.service.AuthorService.class)
						.search(null, 100, 30).stream().map(a -> a.displayName()).toList();
				var tagsPast30 = running.context().getBean(com.yuutara.illustrationarchive.service.TagService.class)
						.search(null, 100, 30).stream().map(t -> t.name()).toList();
				Files.writeString(home.resolve("acceptance-library.txt"), "works=" + built.works() + "\nimages=" + built.images()
						+ "\nmultiPageIds=" + built.multiPageIds() + "\nmissingThumbnailAssetId=" + built.missingThumbnailAssetId()
						+ "\ngifAssetId=" + built.gifAssetId() + "\nauthorsPast30=" + authorsPast30 + "\ntagsPast30=" + tagsPast30
						+ "\n", StandardCharsets.UTF_8);
			}
			seedInbox(jdbc, Integer.getInteger("c.acceptance.inbox", 0));
			Files.writeString(home.resolve("acceptance-session.txt"), running.sessionUri() + "\n", StandardCharsets.UTF_8);
			Instant deadline = Instant.now().plus(Duration.ofHours(2));
			while (!Files.exists(stop) && Instant.now().isBefore(deadline)) {
				LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
			}
		} finally {
			running.context().close();
			Files.deleteIfExists(home.resolve("acceptance-session.txt"));
			((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
		}
	}

	private static void seedInbox(JdbcTemplate jdbc, int posts) {
		if (posts == 0 || jdbc.queryForObject("SELECT COUNT(*) FROM x_like_item", Integer.class) > 0) {
			return;
		}
		var assets = jdbc.queryForList("SELECT id FROM asset WHERE mime_type <> 'image/gif' ORDER BY id", Long.class);
		for (int post = 0; post < posts; post++) {
			String id = Long.toString(2_000_000_000_000_000_000L + post);
			jdbc.update("INSERT INTO x_like_item (x_post_id, x_author_id, author_username, author_display_name, post_text,"
					+ " post_created_at, status, discovered_at, updated_at, likes_order) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP,"
					+ " 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)", id, "90" + post % 5, "artist_" + post % 5, "画师 " + post % 5,
					post % 3 == 0 ? "" : "今日のらくがき #" + post + "\n夏の終わりの空を描きました。", posts - post);
			long itemId = jdbc.queryForObject("SELECT id FROM x_like_item WHERE x_post_id = ?", Long.class, id);
			int pictures = post % 4 == 1 ? 3 : 1;
			for (int page = 0; page < pictures; page++) {
				jdbc.update("INSERT INTO x_like_media (x_like_item_id, media_key, sort_order, media_type, source_url, width, height)"
						+ " VALUES (?, ?, ?, 'photo', ?, 900, 1200)", itemId, id + "-" + page, page,
						"/api/assets/" + assets.get((post * 3 + page) % assets.size()) + "/content");
			}
		}
	}
}
