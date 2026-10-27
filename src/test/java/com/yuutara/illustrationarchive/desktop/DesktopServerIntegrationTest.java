package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.service.DuplicateIllustrationException;
import com.yuutara.illustrationarchive.service.IllustrationImportResult;
import com.yuutara.illustrationarchive.service.IllustrationImportService;
import com.yuutara.illustrationarchive.service.IllustrationPersistenceService;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the real desktop PoC (Spring context, Tomcat on 127.0.0.1, file-based H2, Flyway, local media)
 * through the same {@link DesktopServer#start} used by the launcher. Each test uses its own data home.
 */
class DesktopServerIntegrationTest {

	@TempDir
	Path home;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<DesktopServer.Running> started = new ArrayList<>();

	@AfterEach
	void closeServers() {
		started.forEach(running -> running.context().close());
		// Logback outlives a closed context inside one JVM; release desktop.log so @TempDir can be deleted.
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void localWorkflowPersistsAcrossRestartAndReleasesDatabaseAndPort() throws Exception {
		DesktopServer.Running first = start();
		var environment = first.context().getEnvironment();
		assertTrue(environment.getProperty("spring.datasource.url").startsWith("jdbc:h2:file:" + home));
		assertNull(environment.getProperty("ILLUSTRATION_ARCHIVE_DB_URL"), "MySQL local config must not be imported");
		assertEquals("false", environment.getProperty("x.api.enabled"));
		assertEquals("false", environment.getProperty("ai.analysis.enabled"));
		assertEquals(7, jdbc(first).queryForObject(
				"SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE AND \"version\" IS NOT NULL", Integer.class));

		String cookie = openSession(first);
		HttpResponse<String> capabilities = send(first, cookie, get(first, "/ui-capabilities.js"));
		assertEquals(200, capabilities.statusCode());
		assertTrue(capabilities.body().contains("xImport: false") && capabilities.body().contains("aiAnalysis: false"),
				capabilities.body());
		byte[] red = png(Color.RED);
		HttpResponse<String> imported = send(first, cookie, multipart(first, "/api/illustrations/import",
				List.of(new Part("red.png", red), new Part("blue.png", png(Color.BLUE)), new Part("red-copy.png", red))));
		assertEquals(200, imported.statusCode(), imported.body());
		assertTrue(imported.body().contains("\"successCount\":2"), imported.body());
		assertTrue(imported.body().contains("\"duplicateCount\":1"), imported.body());

		long illustrationId = jdbc(first).queryForObject(
				"SELECT illustration_id FROM asset WHERE original_filename = 'red.png'", Long.class);
		long assetId = jdbc(first).queryForObject("SELECT id FROM asset WHERE illustration_id = ?", Long.class, illustrationId);
		String storageKey = jdbc(first).queryForObject("SELECT storage_key FROM asset WHERE id = ?", String.class, assetId);
		assertFalse(Path.of(storageKey).isAbsolute(), "storage_key must stay relative");

		long tagId = createdId(send(first, cookie, json(first, "POST", "/api/tags", "{\"name\":\"Blue Sky\"}")));
		long authorId = createdId(send(first, cookie, json(first, "POST", "/api/authors",
				"{\"displayName\":\"Alice Painter\",\"xUsername\":\"alice_art\"}")));
		HttpResponse<String> patched = send(first, cookie, json(first, "PATCH", "/api/illustrations/" + illustrationId,
				"{\"title\":\"Morning Study\",\"note\":\"Warm light\",\"authorId\":" + authorId
						+ ",\"tagIds\":[" + tagId + "]}"));
		assertEquals(204, patched.statusCode(), patched.body());
		HttpResponse<String> duplicateTag = send(first, cookie, json(first, "POST", "/api/tags", "{\"name\":\"blue sky\"}"));
		assertEquals(409, duplicateTag.statusCode(), duplicateTag.body());
		assertTrue(duplicateTag.body().contains("DUPLICATE_TAG"), duplicateTag.body());
		assertEquals(1, jdbc(first).queryForObject("SELECT COUNT(*) FROM tag", Integer.class),
				"tag names stay case-insensitively unique, as with utf8mb4_unicode_ci");

		for (String query : List.of("morning", "SKY", "@alice_art", "warm")) {
			HttpResponse<String> search = send(first, cookie, get(first, "/api/illustrations?q=" + query));
			assertTrue(search.body().contains("\"totalElements\":1") && search.body().contains("Morning Study"),
					query + " -> " + search.body());
		}

		HttpResponse<byte[]> content = http.send(request(first, cookie, "/api/assets/" + assetId + "/content").GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, content.statusCode());
		assertArrayEquals(red, content.body());
		assertEquals(200, send(first, cookie, get(first, "/api/assets/" + assetId + "/thumbnail")).statusCode());
		assertEquals(2, originalFiles().size());

		int firstPort = first.port();
		first.context().close();
		started.remove(first);
		try (ServerSocket ignored = new ServerSocket(firstPort, 1, InetAddress.getLoopbackAddress())) {
			// Exit released the port.
		}
		assertFalse(Files.exists(home.resolve("data").resolve("archive.lock.db")), "Exit released the H2 file lock");

		DesktopServer.Running second = start();
		assertEquals(403, send(second, cookie, get(second, "/api/illustrations")).statusCode(),
				"a previous launch's session must not work after restart");
		String secondCookie = openSession(second);
		HttpResponse<String> gallery = send(second, secondCookie, get(second, "/api/illustrations"));
		assertTrue(gallery.body().contains("\"totalElements\":2") && gallery.body().contains("Morning Study"), gallery.body());
		HttpResponse<String> detail = send(second, secondCookie, get(second, "/api/illustrations/" + illustrationId));
		assertTrue(detail.body().contains("Alice Painter") && detail.body().contains("Blue Sky"), detail.body());

		assertEquals(204, send(second, secondCookie, request(second, secondCookie, "/api/illustrations/" + illustrationId)
				.DELETE().build()).statusCode());
		assertEquals(0, jdbc(second).queryForObject("SELECT COUNT(*) FROM asset WHERE id = ?", Integer.class, assetId));
		assertFalse(Files.exists(media().resolve(storageKey)));
		assertEquals(1, originalFiles().size());
		try (Stream<Path> thumbnails = Files.walk(media().resolve("thumbnails"))) {
			assertEquals(1, thumbnails.filter(Files::isRegularFile).count());
		}
	}

	@Test
	void cGalleryIsServedBehindTheLaunchSessionAndReadsAPagedMultiPageLibraryAcrossRestart() throws Exception {
		DesktopServer.Running first = start();
		List<String> pageFiles = List.of("/c/", "/c/archive-client.js", "/c/c-gallery.js", "/c/fx.js", "/c/theme-mode.js",
				"/c/gallery.css", "/c/fx.css", "/c/theme-paper.css", "/c/c.css");
		for (String path : pageFiles) {
			assertEquals(403, send(first, null, get(first, path)).statusCode(), path + " must need the launch session");
		}
		String cookie = openSession(first);
		for (String path : pageFiles) {
			assertEquals(200, send(first, cookie, get(first, path)).statusCode(), path);
		}
		assertTrue(send(first, cookie, get(first, "/c/")).body().contains("新版图库"));
		String oldGallery = send(first, cookie, get(first, "/")).body();
		assertTrue(oldGallery.contains("browse-toolbar") && oldGallery.contains("app.js"), "old gallery stays at /");
		assertTrue(oldGallery.contains("href=\"/c/\""), "old gallery links to the new one");

		var library = CGalleryTestLibrary.build(first, 24);
		assertEquals(24 - (1 + 2 + 3), library.works(), "groups of 2, 3 and 4 pages merge into three works");
		var gallery = first.context().getBean(com.yuutara.illustrationarchive.service.IllustrationGalleryService.class);
		var query = com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery.withIds(null, null, null);
		var page0 = gallery.getGallery(0, 10, query);
		var page1 = gallery.getGallery(1, 10, query);
		var page2 = gallery.getGallery(2, 10, query);
		assertEquals(library.works(), page0.totalElements());
		assertEquals(2, page0.totalPages());
		assertEquals(List.of(10, 8, 0), List.of(page0.items().size(), page1.items().size(), page2.items().size()));
		var seen = new java.util.HashSet<Long>();
		Stream.of(page0, page1).flatMap(p -> p.items().stream()).forEach(item -> assertTrue(seen.add(item.id()), "no work twice"));
		assertEquals(library.works(), seen.size());
		for (long id : library.multiPageIds()) {
			var item = Stream.of(page0, page1).flatMap(p -> p.items().stream()).filter(i -> i.id() == id).findFirst().orElseThrow();
			assertTrue(item.assetCount() >= 2, "multi-page work " + id);
			assertEquals(item.assetCount(), item.assets().size());
			for (int n = 0; n < item.assets().size(); n++) {
				assertEquals(n, item.assets().get(n).sortOrder(), "pages are listed in order");
			}
			assertEquals(item.assets().get(0).id(), item.coverAssetId(), "the first page is the cover");
		}
		HttpResponse<String> http0 = send(first, cookie, get(first, "/api/illustrations?page=0&size=10"));
		assertEquals(200, http0.statusCode());
		assertTrue(http0.body().contains("\"totalElements\":" + library.works()), http0.body());

		// Thumbnail fallback inputs: a GIF has no thumbnail, and one JPEG lost its thumbnail file; both originals load.
		for (long assetId : List.of(library.gifAssetId(), library.missingThumbnailAssetId())) {
			assertEquals(404, send(first, cookie, get(first, "/api/assets/" + assetId + "/thumbnail")).statusCode());
			assertEquals(200, send(first, cookie, get(first, "/api/assets/" + assetId + "/content")).statusCode());
		}

		first.context().close();
		started.remove(first);
		DesktopServer.Running second = start();
		String secondCookie = openSession(second);
		assertEquals(403, send(second, cookie, get(second, "/c/")).statusCode(), "the previous launch's session is gone");
		assertEquals(200, send(second, secondCookie, get(second, "/c/")).statusCode());
		HttpResponse<String> afterRestart = send(second, secondCookie, get(second, "/api/illustrations?page=1&size=10"));
		assertTrue(afterRestart.body().contains("\"totalElements\":" + library.works()), afterRestart.body());
		long multi = library.multiPageIds().get(library.multiPageIds().size() - 1);
		HttpResponse<String> detail = send(second, secondCookie, get(second, "/api/illustrations/" + multi));
		assertTrue(detail.body().contains("c-test-") && detail.body().contains("系列"), detail.body());
	}

	@Test
	void localAccessGuardRejectsRequestsWithoutTheLaunchSession() throws Exception {
		DesktopServer.Running running = start();
		assertEquals(403, send(running, null, get(running, "/api/illustrations")).statusCode());
		assertEquals(403, send(running, null, get(running, "/")).statusCode());
		assertEquals(403, send(running, null, get(running, LocalAccessFilter.SESSION_PATH + "?token=" + "0".repeat(64)))
				.statusCode());

		HttpResponse<String> session = send(running, null, get(running, LocalAccessFilter.SESSION_PATH + "?token="
				+ running.accessToken()));
		String setCookie = session.headers().firstValue("Set-Cookie").orElseThrow();
		assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"), setCookie);
		String cookie = setCookie.substring(0, setCookie.indexOf(';'));

		assertTrue(rawGet(running, "evil.example:" + running.port(), cookie).startsWith("HTTP/1.1 403"),
				"DNS-rebinding Host header must be rejected");
		assertTrue(rawGet(running, "localhost:" + running.port(), cookie).startsWith("HTTP/1.1 200"));

		HttpRequest crossOrigin = request(running, cookie, "/api/tags").header("Origin", "http://localhost:3000")
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build();
		assertEquals(403, send(running, cookie, crossOrigin).statusCode());
		assertEquals(0, jdbc(running).queryForObject("SELECT COUNT(*) FROM tag", Integer.class));
		HttpRequest sameOrigin = request(running, cookie, "/api/tags").header("Origin", "http://127.0.0.1:" + running.port())
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build();
		assertEquals(201, send(running, cookie, sameOrigin).statusCode());

		InetAddress lanAddress = reachableSiteLocalAddress();
		if (lanAddress != null) {
			try (Socket socket = new Socket()) {
				// Windows retries a refused SYN for about two seconds, so a timeout is also "not listening".
				assertThrows(IOException.class,
						() -> socket.connect(new InetSocketAddress(lanAddress, running.port()), 5000),
						"server must listen on 127.0.0.1 only");
			}
		}
	}

	@Test
	void desktopIgnoresOutsideConnectionSettingsAndBlankIntegrationNumbers() throws Exception {
		// System properties outrank config files like environment variables do (SPRING_FLYWAY_URL, X_SYNC_MAX_PAGES=).
		String elsewhere = "jdbc:h2:mem:elsewhere;MODE=MySQL";
		Map<String, String> hostile = new java.util.LinkedHashMap<>();
		hostile.put("spring.flyway.url", elsewhere);
		hostile.put("spring.flyway.user", "intruder");
		hostile.put("spring.flyway.password", "x");
		hostile.put("spring.datasource.hikari.jdbc-url", elsewhere);
		hostile.put("spring.datasource.hikari.username", "intruder");
		for (String variable : List.of("AI_ANALYSIS_TIMEOUT_SECONDS", "X_API_DEFAULT_PAGE_SIZE", "X_API_DEFAULT_MAX_PAGES",
				"X_SYNC_RECENT_PAGE_SIZE", "X_SYNC_RECENT_MAX_PAGES", "X_SYNC_MAX_PAGES", "X_SYNC_MAX_SCANNED", "X_SYNC_MAX_NEW")) {
			hostile.put(variable, "");
		}
		hostile.forEach(System::setProperty);
		try {
			DesktopServer.Running running = start();
			String expected = "jdbc:h2:file:" + home.resolve("data").resolve("archive");
			var flyway = running.context().getBean(org.flywaydb.core.Flyway.class);
			try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
				assertTrue(connection.getMetaData().getURL().startsWith(expected), connection.getMetaData().getURL());
			}
			try (var connection = running.context().getBean(javax.sql.DataSource.class).getConnection()) {
				assertTrue(connection.getMetaData().getURL().startsWith(expected), connection.getMetaData().getURL());
			}
			assertEquals(7, flyway.info().applied().length, "migrations ran against the PoC database");
			var environment = running.context().getEnvironment();
			assertEquals("90", environment.getProperty("ai.analysis.timeout-seconds"));
			assertEquals("6", environment.getProperty("x.sync.max-pages"));
		} finally {
			hostile.keySet().forEach(System::clearProperty);
		}
	}

	@Test
	void springTransactionRollsBackIllustrationWhenAssetInsertFails() {
		DesktopServer.Running running = start();
		var persistence = running.context().getBean(IllustrationPersistenceService.class);
		persistence.persist(new StoredFile("a.png", "2026-10/same-key.png", "image/png", 1, "a".repeat(64)));

		assertThrows(DuplicateKeyException.class, () -> persistence.persist(
				new StoredFile("b.png", "2026-10/same-key.png", "image/png", 1, "b".repeat(64))));
		assertEquals(1, jdbc(running).queryForObject("SELECT COUNT(*) FROM illustration", Integer.class),
				"the illustration row inserted before the failing asset insert must be rolled back");
	}

	@Test
	void databaseDuplicateFoundAfterLockWaitDeletesTheStoredFile() throws Exception {
		DesktopServer.Running running = start();
		var importService = running.context().getBean(IllustrationImportService.class);
		var transactions = new TransactionTemplate(running.context().getBean(
				org.springframework.transaction.PlatformTransactionManager.class));
		byte[] image = png(Color.GREEN);
		String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?>[] racingImport = new Future<?>[1];
			long existingIllustrationId = transactions.execute(status -> {
				jdbc(running).update("INSERT INTO illustration (title) VALUES ('first writer')");
				long illustrationId = jdbc(running).queryForObject("SELECT MAX(id) FROM illustration", Long.class);
				jdbc(running).update("INSERT INTO asset (illustration_id, original_filename, storage_key, mime_type, "
						+ "file_size, sort_order, sha256) VALUES (?, 'first.png', '2026-10/first.png', 'image/png', 1, 0, ?)",
						illustrationId, sha256);
				// The uncommitted row is invisible to the import's pre-check, so the import reaches its own
				// INSERT and must wait on H2's unique-index lock until this transaction commits.
				racingImport[0] = executor.submit(() -> importService.importSingle(
						new MockMultipartFile("file", "green.png", "image/png", image)));
				awaitStoredFileWhileImportWaits(racingImport[0]);
				return illustrationId;
			});

			var failure = assertThrows(java.util.concurrent.ExecutionException.class,
					() -> racingImport[0].get(20, TimeUnit.SECONDS));
			var duplicate = assertInstanceOf(DuplicateIllustrationException.class, failure.getCause());
			assertEquals(existingIllustrationId, duplicate.illustrationId());
			assertEquals(1, jdbc(running).queryForObject("SELECT COUNT(*) FROM asset", Integer.class));
			assertEquals(List.of(), originalFiles(), "the losing import's stored file must be compensated");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void concurrentImportsOfTheSameImageKeepOneAssetAndOneFile() throws Exception {
		DesktopServer.Running running = start();
		var importService = running.context().getBean(IllustrationImportService.class);
		byte[] image = png(Color.ORANGE);
		int threads = 8;
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<IllustrationImportResult>> imports = Collections.nCopies(threads, () -> {
				ready.countDown();
				go.await();
				return importService.importSingle(new MockMultipartFile("file", "orange.png", "image/png", image));
			});
			List<Future<IllustrationImportResult>> futures = new ArrayList<>();
			imports.forEach(task -> futures.add(executor.submit(task)));
			ready.await();
			go.countDown();

			int successes = 0;
			int duplicates = 0;
			for (Future<IllustrationImportResult> future : futures) {
				try {
					future.get(30, TimeUnit.SECONDS);
					successes++;
				} catch (java.util.concurrent.ExecutionException exception) {
					assertInstanceOf(DuplicateIllustrationException.class, exception.getCause());
					duplicates++;
				}
			}
			assertEquals(1, successes);
			assertEquals(threads - 1, duplicates);
			assertEquals(1, jdbc(running).queryForObject("SELECT COUNT(*) FROM asset", Integer.class));
			assertEquals(1, jdbc(running).queryForObject("SELECT COUNT(*) FROM illustration", Integer.class));
			assertEquals(1, originalFiles().size());
		} finally {
			executor.shutdownNow();
		}
	}

	private DesktopServer.Running start() {
		DesktopServer.Running running = DesktopServer.start(home, 0, true);
		started.add(running);
		return running;
	}

	private static JdbcTemplate jdbc(DesktopServer.Running running) {
		return running.context().getBean(JdbcTemplate.class);
	}

	private Path media() {
		return home.resolve("media");
	}

	private List<Path> originalFiles() throws IOException {
		if (!Files.exists(media())) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(media())) {
			return files.filter(Files::isRegularFile)
					.filter(path -> !media().relativize(path).startsWith("thumbnails"))
					.toList();
		}
	}

	/**
	 * The import stores its file before touching the database. Once the file exists and the import is still
	 * running well afterwards, it is waiting on H2's unique index (H2 does not list this wait in BLOCKER_ID).
	 */
	private void awaitStoredFileWhileImportWaits(Future<?> racingImport) {
		try {
			for (int attempt = 0; attempt < 100 && originalFiles().isEmpty(); attempt++) {
				java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
			assertEquals(1, originalFiles().size(), "the racing import should have stored its file");
		} catch (IOException exception) {
			throw new java.io.UncheckedIOException(exception);
		}
		java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1000));
		assertFalse(racingImport.isDone(), "the racing import must wait for the uncommitted row, not fail fast");
	}

	private String openSession(DesktopServer.Running running) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(running.sessionUri()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, response.statusCode());
		String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
		return setCookie.substring(0, setCookie.indexOf(';'));
	}

	private HttpRequest.Builder request(DesktopServer.Running running, String cookie, String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(running.baseUri().resolve(path)).timeout(Duration.ofSeconds(30));
		return cookie == null ? builder : builder.header("Cookie", cookie);
	}

	private HttpRequest get(DesktopServer.Running running, String path) {
		return HttpRequest.newBuilder(running.baseUri().resolve(path)).GET().build();
	}

	private HttpRequest json(DesktopServer.Running running, String method, String path, String body) {
		return HttpRequest.newBuilder(running.baseUri().resolve(path)).header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
	}

	private record Part(String filename, byte[] content) {
	}

	private HttpRequest multipart(DesktopServer.Running running, String path, List<Part> parts) throws IOException {
		String boundary = "----desktop-poc-test";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (Part part : parts) {
			body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
					+ part.filename() + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			body.write(part.content());
			body.write("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return HttpRequest.newBuilder(running.baseUri().resolve(path))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
	}

	/** Adds the session cookie to a prepared request; null sends the request without one. */
	private HttpResponse<String> send(DesktopServer.Running running, String cookie, HttpRequest prepared) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(prepared.uri()).timeout(Duration.ofSeconds(30));
		prepared.headers().map().forEach((name, values) -> {
			if (!name.equalsIgnoreCase("Cookie")) {
				values.forEach(value -> builder.header(name, value));
			}
		});
		if (cookie != null) {
			builder.header("Cookie", cookie);
		}
		builder.method(prepared.method(), prepared.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static long createdId(HttpResponse<String> response) {
		assertEquals(201, response.statusCode(), response.body());
		var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(response.body());
		assertTrue(matcher.find(), response.body());
		return Long.parseLong(matcher.group(1));
	}

	/** java.net.http forbids overriding Host, so the rebinding case uses a raw HTTP/1.1 request. */
	private static String rawGet(DesktopServer.Running running, String host, String cookie) throws IOException {
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), running.port())) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			out.write(("GET /api/tags HTTP/1.1\r\nHost: " + host + "\r\nCookie: " + cookie
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			InputStream in = socket.getInputStream();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** A LAN address this host can actually connect to (control socket), or null when none exists. */
	private static InetAddress reachableSiteLocalAddress() throws IOException {
		for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!network.isUp() || network.isLoopback()) {
				continue;
			}
			for (InetAddress address : Collections.list(network.getInetAddresses())) {
				if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) {
					continue;
				}
				try (ServerSocket control = new ServerSocket(0, 1, address); Socket probe = new Socket()) {
					probe.connect(new InetSocketAddress(address, control.getLocalPort()), 1000);
					return address;
				} catch (IOException unreachable) {
					// Virtual or filtered adapter; try the next one.
				}
			}
		}
		return null;
	}

	private static byte[] png(Color color) throws IOException {
		BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 64, 48);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
