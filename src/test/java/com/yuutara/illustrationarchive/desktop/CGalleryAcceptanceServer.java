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
				Files.writeString(home.resolve("acceptance-library.txt"), "works=" + built.works() + "\nimages=" + built.images()
						+ "\nmultiPageIds=" + built.multiPageIds() + "\nmissingThumbnailAssetId=" + built.missingThumbnailAssetId()
						+ "\ngifAssetId=" + built.gifAssetId() + "\n", StandardCharsets.UTF_8);
			}
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
}
