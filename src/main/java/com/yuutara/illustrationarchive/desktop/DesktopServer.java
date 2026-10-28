package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.IllustrationArchiveApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts the existing Spring Boot application as a Windows desktop PoC.
 *
 * <p>Everything that isolates the PoC from the MySQL setup is passed as command-line arguments, the
 * highest-precedence property source. Environment variables such as {@code X_API_ENABLED} or
 * {@code SPRING_DATASOURCE_URL} would otherwise override a profile file through relaxed binding.
 */
public final class DesktopServer {

	public static final String PROFILE = "desktop";
	public static final String ACCESS_TOKEN_PROPERTY = "illustration-archive.desktop.access-token";
	/**
	 * MODE=MySQL keeps repository SQL portable; IGNORECASE matches the utf8mb4_unicode_ci comparisons
	 * used by search and unique tag names. Repository contract tests reuse the same flags.
	 */
	public static final String H2_COMPATIBILITY =
			";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;IGNORECASE=TRUE";

	private DesktopServer() {
	}

	public record Running(ConfigurableApplicationContext context, int port, String accessToken) {

		public URI baseUri() {
			return URI.create("http://127.0.0.1:" + port + "/");
		}

		/** One-time entry URL: the filter turns the token into a SameSite=Strict cookie. */
		public URI sessionUri() {
			return baseUri().resolve(LocalAccessFilter.SESSION_PATH + "?token=" + accessToken);
		}
	}

	/** {@code %LOCALAPPDATA%\IllustrationArchivePoC}, overridable for tests and manual acceptance. */
	public static Path defaultHome() {
		String override = System.getProperty("illustration-archive.desktop.home",
				System.getenv("ILLUSTRATION_ARCHIVE_DESKTOP_HOME"));
		if (override != null && !override.isBlank()) {
			return Path.of(override).toAbsolutePath().normalize();
		}
		String localAppData = System.getenv("LOCALAPPDATA");
		Path base = localAppData == null || localAppData.isBlank()
				? Path.of(System.getProperty("user.home"), "AppData", "Local")
				: Path.of(localAppData);
		return base.resolve("IllustrationArchivePoC").toAbsolutePath().normalize();
	}

	public static Running start(Path home, int port, boolean headless) {
		String accessToken = HexFormat.of().formatHex(randomBytes());
		SpringApplication application = new SpringApplication(IllustrationArchiveApplication.class);
		application.setAdditionalProfiles(PROFILE);
		application.setHeadless(headless);
		ConfigurableApplicationContext context = application.run(arguments(home, port, accessToken));
		int actualPort = ((WebServerApplicationContext) context).getWebServer().getPort();
		return new Running(context, actualPort, accessToken);
	}

	static Map<String, String> properties(Path home, int port, String accessToken) {
		Path root = home.toAbsolutePath().normalize();
		Map<String, String> properties = new LinkedHashMap<>();
		// Optional, PoC-only override file; the MySQL ./config/application-local.properties is never read.
		properties.put("illustration-archive.local-config", root.resolve("config").resolve("desktop.properties").toString());

		// Spring, not H2's own shutdown hook, closes the database.
		String h2Url = "jdbc:h2:file:" + root.resolve("data").resolve("archive")
				+ H2_COMPATIBILITY + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
		properties.put("spring.datasource.url", h2Url);
		properties.put("spring.datasource.driver-class-name", "org.h2.Driver");
		properties.put("spring.datasource.username", "sa");
		properties.put("spring.datasource.password", "");
		properties.put("spring.datasource.hikari.maximum-pool-size", "5");
		// Hikari and Flyway each accept their own connection settings, which would otherwise win over the
		// datasource above (e.g. SPRING_FLYWAY_URL). Pin both to the PoC database.
		properties.put("spring.datasource.hikari.jdbc-url", h2Url);
		properties.put("spring.datasource.hikari.username", "sa");
		properties.put("spring.datasource.hikari.password", "");
		properties.put("spring.flyway.enabled", "true");
		properties.put("spring.flyway.url", h2Url);
		properties.put("spring.flyway.user", "sa");
		properties.put("spring.flyway.password", "");
		properties.put("spring.flyway.driver-class-name", "org.h2.Driver");
		properties.put("spring.flyway.locations", "classpath:db/migration-h2");

		properties.put("storage.type", "local");
		properties.put("illustration-archive.storage.root-dir", root.resolve("media").toString());

		properties.put("server.address", "127.0.0.1");
		properties.put("server.port", Integer.toString(port));
		properties.put("server.shutdown", "graceful");
		properties.put("spring.lifecycle.timeout-per-shutdown-phase", "15s");
		properties.put(ACCESS_TOKEN_PROPERTY, accessToken);

		// External integrations and one-off maintenance are out of scope for the PoC.
		properties.put("x.api.enabled", "false");
		properties.put("x.oauth.client-id", "");
		properties.put("x.oauth.client-secret", "");
		properties.put("x.oauth.credential-file", root.resolve("config").resolve("x-oauth-disabled.properties").toString());
		properties.put("ai.analysis.enabled", "false");
		properties.put("gemini.api-key", "");
		// The disabled integrations still bind these numbers at startup; a blank X_SYNC_MAX_PAGES= or
		// AI_ANALYSIS_TIMEOUT_SECONDS= must not stop the gallery from opening. Values are the usual defaults.
		properties.put("ai.analysis.timeout-seconds", "90");
		properties.put("x.api.default-page-size", "5");
		properties.put("x.api.default-max-pages", "3");
		properties.put("x.sync.recent-page-size", "25");
		properties.put("x.sync.recent-max-pages", "2");
		properties.put("x.sync.max-pages", "6");
		properties.put("x.sync.max-scanned", "100");
		properties.put("x.sync.max-new", "50");
		properties.put("illustration.maintenance.sha256-backfill", "false");
		properties.put("illustration.maintenance.thumbnail-backfill", "false");
		properties.put("illustration-archive.ui.x-import", "false");
		properties.put("illustration-archive.ui.ai-analysis", "false");

		properties.put("logging.file.name", root.resolve("logs").resolve("desktop.log").toString());
		return properties;
	}

	private static String[] arguments(Path home, int port, String accessToken) {
		return properties(home, port, accessToken).entrySet().stream()
				.map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
				.toArray(String[]::new);
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}
}
