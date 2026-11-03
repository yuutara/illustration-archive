package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.IllustrationArchiveApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Starts the existing Spring Boot application as the Windows desktop app.
 *
 * <p>Everything that isolates the app from the MySQL setup is passed as command-line arguments, the
 * highest-precedence property source. Environment variables such as {@code X_API_ENABLED} or
 * {@code SPRING_DATASOURCE_URL} would otherwise override a profile file through relaxed binding.
 */
public final class DesktopServer {

	public static final String PROFILE = "desktop";
	public static final String ACCESS_TOKEN_PROPERTY = "illustration-archive.desktop.access-token";
	public static final String HOME_PROPERTY = "illustration-archive.desktop.home-dir";
	/** The fixed loopback port of the OAuth callback, which users register in their X app. */
	public static final int X_CALLBACK_PORT = 47392;
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

	/** {@code %LOCALAPPDATA%\IllustrationArchive}, overridable for tests and manual acceptance. */
	public static Path defaultHome() {
		String override = System.getProperty("illustration-archive.desktop.home",
				System.getenv("ILLUSTRATION_ARCHIVE_DESKTOP_HOME"));
		if (override != null && !override.isBlank()) {
			return Path.of(override).toAbsolutePath().normalize();
		}
		return homeUnder(localAppData()).toAbsolutePath().normalize();
	}

	/** Where a brand-new library belongs, whatever {@link #defaultHome()} currently resolves to. */
	static Path defaultNewHome() {
		return localAppData().resolve("IllustrationArchive").toAbsolutePath().normalize();
	}

	private static Path localAppData() {
		String localAppData = System.getenv("LOCALAPPDATA");
		return localAppData == null || localAppData.isBlank()
				? Path.of(System.getProperty("user.home"), "AppData", "Local")
				: Path.of(localAppData);
	}

	/** A library created before the rename stays where it is: nothing is moved on the user's behalf. */
	static Path homeUnder(Path base) {
		Path current = base.resolve("IllustrationArchive");
		Path earlier = base.resolve("IllustrationArchivePoC");
		return !Files.exists(current) && Files.exists(earlier) ? earlier : current;
	}

	public static Running start(Path home, int port, boolean headless) {
		return start(home, port, headless, Map.of());
	}

	/** {@code overrides} is the one way past the pinned values below; tests use it to reach a fake X server. */
	static Running start(Path home, int port, boolean headless, Map<String, String> overrides) {
		return start(home, port, headless, newAccessToken(), overrides);
	}

	/**
	 * The launcher keeps one token while it runs, so a restart for a backup or restore leaves the window's session
	 * cookie valid.
	 */
	static Running start(Path home, int port, boolean headless, String accessToken, Map<String, String> overrides) {
		SpringApplication application = new SpringApplication(IllustrationArchiveApplication.class);
		application.setAdditionalProfiles(PROFILE);
		application.setHeadless(headless);
		Map<String, String> properties = properties(home, port, accessToken);
		properties.put("illustration-archive.desktop.open-external", Boolean.toString(!headless));
		properties.putAll(overrides);
		ConfigurableApplicationContext context = application.run(arguments(properties));
		int actualPort = ((WebServerApplicationContext) context).getWebServer().getPort();
		return new Running(context, actualPort, accessToken);
	}

	/**
	 * Closes the server: requests drain, then the beans, the connection pool and with it the H2 file.
	 *
	 * @return true once the pool is closed, so nothing in the app holds the database open
	 */
	static boolean stop(Running running) {
		DataSource dataSource = running.context().getBean(DataSource.class);
		running.context().close();
		return dataSource instanceof HikariDataSource pool && pool.isClosed();
	}

	static String newAccessToken() {
		return HexFormat.of().formatHex(randomBytes());
	}

	static Map<String, String> properties(Path home, int port, String accessToken) {
		Path root = home.toAbsolutePath().normalize();
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put(HOME_PROPERTY, root.toString());
		// Optional override file; the MySQL ./config/application-local.properties is never read.
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
		// datasource above (e.g. SPRING_FLYWAY_URL). Pin both to the desktop database.
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
		// The window's profile outlives each update: without this, a new EXE can run the previous version's pages.
		properties.put("spring.web.resources.cache.cachecontrol.no-cache", "true");
		// Spring would answer that check from the jar's timestamp, which the build fixes to one value, so every
		// version would look unchanged and the window would keep the pages it cached first.
		properties.put("spring.web.resources.cache.use-last-modified", "false");
		properties.put(ACCESS_TOKEN_PROPERTY, accessToken);

		// X and Gemini are on, but neither can reach out until the user connects an account or saves a key in
		// Settings: without local credentials the code stops before any request. Both use the user's own
		// developer accounts, so no Client Secret or API key ships in the app or is read from the environment.
		properties.put("x.api.enabled", "true");
		properties.put("x.api.base-url", "https://api.x.com");
		properties.put("illustration-archive.desktop.x-authorize-url", "https://x.com/i/oauth2/authorize");
		properties.put("x.oauth.client-id", "");
		properties.put("x.oauth.client-secret", "");
		properties.put("x.oauth.credential-file", root.resolve("config").resolve("x-oauth-tokens.properties").toString());
		properties.put("illustration-archive.desktop.x-callback-port", Integer.toString(X_CALLBACK_PORT));
		properties.put("ai.analysis.enabled", "true");
		properties.put("gemini.base-url", "https://generativelanguage.googleapis.com");
		properties.put("gemini.api-key", "");
		properties.put("gemini.credential-file", root.resolve("config").resolve("gemini-key.txt").toString());
		properties.put("gemini.model", "gemini-3.1-flash-lite");
		// A blank X_SYNC_MAX_PAGES= or AI_ANALYSIS_TIMEOUT_SECONDS= in the environment must not stop the
		// gallery from opening. Values are the usual defaults; the sync budget also caps each click's cost.
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
		properties.put("illustration-archive.ui.desktop", "true");

		properties.put("logging.file.name", root.resolve("logs").resolve("desktop.log").toString());
		return properties;
	}

	private static String[] arguments(Map<String, String> properties) {
		return properties.entrySet().stream()
				.map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
				.toArray(String[]::new);
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}
}
