package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real desktop server with the optional phone connector: its own H2 home, real Tomcat, real HTTP and real cookies.
 * The phone connector binds only the selected IPv4 address; tests therefore dial that address, not loopback.
 */
class MobileAccessIntegrationTest {

	@TempDir
	Path home;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<DesktopServer.Running> started = new ArrayList<>();

	@AfterEach
	void closeServers() {
		started.forEach(running -> {
			try {
				running.context().close();
			} catch (RuntimeException alreadyClosed) {
				// A test may have closed it while checking a restart.
			}
		});
		((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).reset();
	}

	@Test
	void phoneAccessIsOffByDefaultAndNoLanConnectorIsOpen() {
		DesktopServer.Running running = start(false, 0);
		MobileAccess mobile = mobile(running);
		MobileAccess.Status status = mobile.status();
		assertFalse(status.enabled(), "the default is loopback only");
		assertFalse(status.active(), "no LAN connector is added");
		assertEquals(-1, status.port());
		assertEquals(1, lanConnectors(running), "only the desktop connector listens");
	}

	@Test
	void anUnpairedPhoneCannotReadTheLibraryOriginalsOrDesktopSettings() throws Exception {
		assumeLan();
		DesktopServer.Running running = start(true, 0);
		String desktopCookie = openSession(running);
		long assetId = importPng(running, desktopCookie, png(Color.RED));
		URI phone = phoneBase(running);

		HttpResponse<String> list = send(phone.resolve("/api/illustrations"), null);
		assertEquals(403, list.statusCode());
		assertTrue(list.body().contains("MOBILE_FORBIDDEN"), list.body());

		HttpResponse<byte[]> original = http.send(HttpRequest.newBuilder(phone.resolve("/api/assets/" + assetId + "/content"))
				.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(403, original.statusCode(), "an unpaired phone must not receive the original bytes");

		assertEquals(403, send(phone.resolve("/api/illustrations/1"), null).statusCode());
		HttpResponse<String> page = send(phone.resolve("/m/"), null);
		assertEquals(302, page.statusCode());
		assertTrue(page.headers().firstValue("Location").orElseThrow().endsWith("/m/pair.html?e=2"));
		assertEquals(403, send(phone.resolve("/m/app.css"), null).statusCode());
		assertEquals(403, send(phone.resolve("/c/"), null).statusCode());
		assertEquals(403, send(phone.resolve("/api/desktop/settings"), null).statusCode());
		assertEquals(403, send(phone.resolve("/api/tags"), null).statusCode());
	}

	@Test
	void pairingRemembersThePhoneAndRevokingItTakesEffectOnTheNextRequest() throws Exception {
		assumeLan();
		DesktopServer.Running running = start(true, 0);
		String desktopCookie = openSession(running);
		long assetId = importPng(running, desktopCookie, png(Color.BLUE));
		MobileAccess mobile = mobile(running);
		MobileAccess.PairingView pairing = mobile.startPairing(null);
		URI phone = phoneBase(running);
		int port = phone.getPort();
		assertEquals(8, pairing.code().length());
		assertTrue(pairing.url().contains("/m/pair?code=" + pairing.code()), pairing.url());
		assertTrue(pairing.qr().startsWith("data:image/svg+xml;base64,"));

		HttpResponse<String> paired = send(phone.resolve("/m/pair?code=" + pairing.code()), null);
		assertEquals(302, paired.statusCode());
		assertTrue(paired.headers().firstValue("Location").orElseThrow().endsWith("/m/"));
		String setCookie = paired.headers().firstValue("Set-Cookie").orElseThrow();
		assertTrue(setCookie.contains(MobileAccess.DEVICE_COOKIE + "="), setCookie);
		assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Lax"), setCookie);
		String cookie = setCookie.substring(0, setCookie.indexOf(';'));

		HttpResponse<String> reused = send(phone.resolve("/m/pair?code=" + pairing.code()), null);
		assertEquals(302, reused.statusCode());
		assertTrue(reused.headers().firstValue("Location").orElseThrow().endsWith("/m/pair.html?e=1"));
		assertTrue(reused.headers().firstValue("Set-Cookie").isEmpty(), "a used code must not issue another cookie");

		HttpResponse<String> list = send(phone.resolve("/api/illustrations"), cookie);
		assertEquals(200, list.statusCode(), list.body());
		assertTrue(list.body().contains("\"totalElements\":1"), list.body());
		HttpResponse<byte[]> original = http.send(HttpRequest.newBuilder(phone.resolve("/api/assets/" + assetId + "/content"))
				.header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, original.statusCode());
		assertArrayEquals(png(Color.BLUE), original.body());
		assertEquals(200, send(phone.resolve("/api/illustrations/1"), cookie).statusCode());
		assertTrue(send(phone.resolve("/m/"), cookie).body().contains("id=\"m-grid\""));
		assertEquals(200, send(phone.resolve("/m/app.css"), cookie).statusCode());
		assertTrue(send(phone.resolve("/m/item.html?id=1"), cookie).body().contains("id=\"m-pages\""));
		assertEquals(200, send(phone.resolve("/c/gallery.css"), cookie).statusCode());
		assertEquals(403, send(phone.resolve("/c/"), cookie).statusCode(), "the phone cannot open the desktop shell");
		assertEquals(403, send(phone.resolve("/api/desktop/settings"), cookie).statusCode());
		assertEquals(403, send(phone.resolve("/api/tags"), cookie, "POST", "{\"name\":\"phone\"}").statusCode(),
				"a paired phone is still read-only");
		assertFalse(rawGet(phone.getHost(), port, phone.getHost() + ":" + port, "/m/../c/settings.html", cookie)
				.startsWith("HTTP/1.1 200"), "a dot-dot path must not escape the exact mobile allowlist");
		assertFalse(rawGet(phone.getHost(), port, "evil.example:" + port, "/api/illustrations", cookie)
				.startsWith("HTTP/1.1 200"), "a rebinding Host header must be refused on the phone lane too");

		String deviceId = mobile.status().devices().get(0).id();
		assertEquals(204, send(running.baseUri().resolve("/api/desktop/mobile/devices/" + deviceId), desktopCookie,
				"DELETE", null).statusCode());
		assertEquals(403, send(phone.resolve("/api/illustrations"), cookie).statusCode(),
				"the old cookie stops working on the very next request");
		assertEquals(403, http.send(HttpRequest.newBuilder(phone.resolve("/api/assets/" + assetId + "/content"))
				.header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
	}

	@Test
	void turningPhoneAccessOffClosesTheLanPortAndTurningItBackOnKeepsThePairing() throws Exception {
		assumeLan();
		DesktopServer.Running running = start(true, 0);
		String desktopCookie = openSession(running);
		MobileAccess mobile = mobile(running);
		MobileAccess.PairingView pairing = mobile.startPairing(null);
		URI phone = phoneBase(running);
		int port = phone.getPort();
		HttpResponse<String> paired = send(phone.resolve("/m/pair?code=" + pairing.code()), null);
		String cookie = paired.headers().firstValue("Set-Cookie").orElseThrow();
		cookie = cookie.substring(0, cookie.indexOf(';'));
		assertEquals(200, send(phone.resolve("/api/illustrations"), cookie).statusCode());

		HttpResponse<String> off = send(running.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie, "PUT",
				"{\"enabled\":false}");
		assertEquals(200, off.statusCode(), off.body());
		MobileAccess.Status stopped = mobile.status();
		assertFalse(stopped.enabled());
		assertFalse(stopped.active());
		assertEquals(1, lanConnectors(running), "the LAN connector is removed, not just hidden");
		assertThrows(IOException.class, () -> connect(phone.getHost(), port), "the old LAN port refuses connections");

		HttpResponse<String> on = send(running.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie, "PUT",
				"{\"enabled\":true}");
		assertEquals(200, on.statusCode(), on.body());
		MobileAccess.Status restarted = mobile.status();
		assertTrue(restarted.active());
		assertTrue(restarted.port() > 0);
		assertEquals(1, restarted.devices().size(), "turning it off does not throw paired phones away");
		URI again = phoneBase(running);
		assertEquals(200, send(again.resolve("/api/illustrations"), cookie).statusCode(),
				"the same phone is remembered when phone access is turned back on");
	}

	@Test
	void headNeverConsumesThePairingCodeOrIssuesACookie() throws Exception {
		assumeLan();
		DesktopServer.Running running = start(true, 0);
		MobileAccess mobile = mobile(running);
		MobileAccess.PairingView pairing = mobile.startPairing(null);
		URI phone = phoneBase(running);

		HttpResponse<String> head = send(phone.resolve("/m/pair?code=" + pairing.code()), null, "HEAD", null);
		assertEquals(405, head.statusCode());
		assertEquals("GET", head.headers().firstValue("Allow").orElseThrow());
		assertTrue(head.headers().firstValue("Set-Cookie").isEmpty(), "HEAD must not issue a device cookie");

		HttpResponse<String> get = send(phone.resolve("/m/pair?code=" + pairing.code()), null);
		assertEquals(302, get.statusCode(), "the code is still usable after HEAD");
		assertTrue(get.headers().firstValue("Set-Cookie").isPresent());
		HttpResponse<String> again = send(phone.resolve("/m/pair?code=" + pairing.code()), null);
		assertEquals(302, again.statusCode());
		assertTrue(again.headers().firstValue("Location").orElseThrow().endsWith("/m/pair.html?e=1"));
		assertTrue(again.headers().firstValue("Set-Cookie").isEmpty());
	}

	@Test
	void theSelectedAddressIsTheOnlyOneListening() throws Exception {
		assumeLan();
		List<String> addresses = LanAddresses.siteLocal();
		DesktopServer.Running running = start(true, 0);
		MobileAccess.Status status = mobile(running).status();
		assertTrue(status.active(), status.error());
		String bound = status.address();
		int port = status.port();
		assertNotNull(bound);
		assertTrue(addresses.contains(bound));

		assertEquals(302, send(URI.create("http://" + bound + ":" + port + "/m/"), null).statusCode());
		String raw = rawGet(bound, port, "127.0.0.1:" + port, "/api/illustrations", null);
		assertTrue(raw.startsWith("HTTP/1.1 403"), "another Host for the same bound port must be refused");
		for (String other : addresses) {
			if (!other.equals(bound)) {
				assertThrows(IOException.class, () -> connect(other, port),
						"the connector must not listen on an address the user did not authorize: " + other);
			}
		}
	}

	@Test
	void theAddressChosenInSettingsIsTheOnlyOneRestoredAfterARestart() throws Exception {
		assumeLan();
		List<String> addresses = LanAddresses.siteLocal();
		String authorized = addresses.get(addresses.size() - 1);
		DesktopServer.Running running = start(true, 0);
		MobileAccess mobile = mobile(running);
		MobileAccess.PairingView pairing = mobile.startPairing(authorized);
		assertEquals(authorized, pairing.address(), "the picked address is the one that gets bound");
		assertEquals(authorized, mobile.status().address());

		URI phone = phoneBase(running);
		String setCookie = send(phone.resolve("/m/pair?code=" + pairing.code()), null)
				.headers().firstValue("Set-Cookie").orElseThrow();
		String cookie = setCookie.substring(0, setCookie.indexOf(';'));
		int oldPort = phone.getPort();

		running.context().close();
		started.remove(running);

		DesktopServer.Running restarted = startExisting(0);
		MobileAccess.Status status = mobile(restarted).status();
		assertTrue(status.active(), status.error());
		assertEquals(authorized, status.address(), "a restart must restore the authorized address, not the first one");
		assertEquals(200, send(URI.create("http://" + authorized + ":" + status.port() + "/api/illustrations"), cookie)
				.statusCode(), "the phone paired on that address is still recognized");
		for (String other : addresses) {
			if (!other.equals(authorized)) {
				assertThrows(IOException.class, () -> connect(other, status.port()),
						"the old or any other address must stay closed: " + other);
			}
		}
		assertThrows(IOException.class, () -> connect(authorized, oldPort));
		assertTrue(Files.readString(home.resolve("config").resolve("settings.properties"), StandardCharsets.UTF_8)
				.contains("mobile.address=" + authorized), "the address is the durable authorization");
	}

	@Test
	void anEnabledSettingWithoutAnAddressWaitsForTheUserInsteadOfPickingTheFirstAdapter() throws Exception {
		assumeLan();
		writeSettings("mobile.enabled=true\n");
		DesktopServer.Running running = startExisting(0);
		MobileAccess mobile = mobile(running);
		MobileAccess.Status waiting = mobile.status();
		assertTrue(waiting.enabled(), "the old switch value is still reported");
		assertFalse(waiting.active(), "nothing may listen until an address is chosen");
		assertEquals(-1, waiting.port());
		assertNull(waiting.address());
		assertTrue(waiting.error().contains("还没有记住"), waiting.error());
		assertEquals(1, lanConnectors(running), "no default adapter was opened automatically");

		String chosen = LanAddresses.siteLocal().get(LanAddresses.siteLocal().size() - 1);
		MobileAccess.Status opened = mobile.setEnabled(true, chosen);
		assertTrue(opened.active(), opened.error());
		assertEquals(chosen, opened.address());
		assertTrue(Files.readString(home.resolve("config").resolve("settings.properties"), StandardCharsets.UTF_8)
				.contains("mobile.address=" + chosen), "the explicit choice is remembered for the next start");
	}

	@Test
	void aMissingSavedAddressKeepsThePortClosedInsteadOfUsingAnotherAdapter() throws Exception {
		assumeLan();
		writeSettings("mobile.enabled=true\nmobile.address=203.0.113.7\n");
		DesktopServer.Running running = startExisting(0);
		MobileAccess.Status status = mobile(running).status();
		assertTrue(status.enabled());
		assertFalse(status.active(), "a missing saved address must not fall back to the first adapter");
		assertEquals(-1, status.port());
		assertNull(status.address());
		assertTrue(status.error().contains("203.0.113.7"), status.error());
		assertEquals(1, lanConnectors(running));
	}

	@Test
	void aBusyPreferredPortFallsBackToAFreePortAndLeavesNoFailedConnector() throws Exception {
		assumeLan();
		String address = LanAddresses.siteLocal().get(0);
		try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName(address))) {
			DesktopServer.Running running = start(true, occupied.getLocalPort());
			MobileAccess.Status status = mobile(running).status();
			assertTrue(status.active(), status.error());
			assertTrue(status.port() > 0);
			assertNotEquals(occupied.getLocalPort(), status.port(), "the occupied port must not be reported as bound");
			assertEquals(2, lanConnectors(running), "the failed preferred connector must be removed");
			assertEquals(302, send(URI.create("http://" + status.address() + ":" + status.port() + "/m/"), null)
					.statusCode());
			assertNotNull(openSession(running), "the local desktop entry still works after the fallback");
		}
	}

	@Test
	void aDisappearingAddressStopsTheListenerAndReportsIt() throws Exception {
		assumeLan();
		DesktopServer.Running running = start(true, 0);
		MobileAccess mobile = mobile(running);
		MobileAccess.Status before = mobile.status();
		assertTrue(before.active(), before.error());
		String bound = before.address();
		int port = before.port();

		MobileAccess.Status after = mobile.statusForAddresses(List.of("192.0.2.1"));
		assertFalse(after.active());
		assertEquals(-1, after.port());
		assertTrue(after.error().contains("不在线"), after.error());
		assertEquals(1, lanConnectors(running));
		assertThrows(IOException.class, () -> connect(bound, port));
	}

	@Test
	void anAddressThatCannotBeBoundLeavesNoConnectorBehind() {
		DesktopServer.Running running = start(false, 0);
		MobileAccess mobile = mobile(running);
		assertThrows(MobileAccess.MobileAccessException.class,
				() -> mobile.openConnectorForTest("203.0.113.1", 0));
		assertEquals(1, lanConnectors(running));
	}

	@Test
	void aFailedCloseIsVisibleAndCanBeSavedAgainWithoutReopeningThePort() throws Exception {
		assumeLan();
		assumeWindowsReadonly();
		DesktopServer.Running running = start(true, 0);
		String desktopCookie = openSession(running);
		MobileAccess mobile = mobile(running);
		MobileAccess.PairingView pairing = mobile.startPairing(null);
		URI phone = phoneBase(running);
		int port = phone.getPort();
		assertEquals(302, send(phone.resolve("/m/pair?code=" + pairing.code()), null).statusCode());

		Path settingsFile = home.resolve("config").resolve("settings.properties");
		setReadonly(settingsFile, true);
		try {
			HttpResponse<String> off = send(running.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie, "PUT",
					"{\"enabled\":false}");
			assertEquals(200, off.statusCode(), off.body());
			MobileAccess.Status failed = mobile.status();
			assertFalse(failed.enabled());
			assertFalse(failed.active());
			assertTrue(failed.error() != null && !failed.error().isBlank(), "the failed save must be visible");
			assertTrue(Files.exists(home.resolve(MobileAccess.OFF_MARKER)), "the close intent must be durable");
			assertThrows(IOException.class, () -> connect(phone.getHost(), port));
			assertEquals(1, failed.devices().size(), "the paired device record stays while the switch is off");
		} finally {
			setReadonly(settingsFile, false);
		}

		HttpResponse<String> retried = send(running.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie, "PUT",
				"{\"enabled\":false}");
		assertEquals(200, retried.statusCode(), retried.body());
		MobileAccess.Status saved = mobile.status();
		assertFalse(saved.enabled());
		assertFalse(saved.active());
		assertTrue(saved.error() == null || saved.error().isBlank(), saved.error());
		assertFalse(Files.exists(home.resolve(MobileAccess.OFF_MARKER)));
		assertTrue(Files.readString(settingsFile, StandardCharsets.UTF_8).contains("mobile.enabled=false"));
	}

	@Test
	void aFailedCloseStaysClosedAcrossARestartAndThePhoneCanPairAgain() throws Exception {
		assumeLan();
		assumeWindowsReadonly();
		DesktopServer.Running running = start(true, 0);
		String desktopCookie = openSession(running);
		MobileAccess mobile = mobile(running);
		MobileAccess.PairingView pairing = mobile.startPairing(null);
		URI phone = phoneBase(running);
		String cookie = send(phone.resolve("/m/pair?code=" + pairing.code()), null)
				.headers().firstValue("Set-Cookie").orElseThrow();
		cookie = cookie.substring(0, cookie.indexOf(';'));
		int oldPort = phone.getPort();

		Path settingsFile = home.resolve("config").resolve("settings.properties");
		setReadonly(settingsFile, true);
		try {
			assertEquals(200, send(running.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie, "PUT",
					"{\"enabled\":false}").statusCode());
			assertTrue(Files.exists(home.resolve(MobileAccess.OFF_MARKER)));
			running.context().close();
			started.remove(running);

			// Restart while settings is still unwritable: the marker must keep it off, not auto-listen.
			DesktopServer.Running stillLocked = startExisting(0);
			MobileAccess.Status off = mobile(stillLocked).status();
			assertFalse(off.enabled());
			assertFalse(off.active());
			assertTrue(off.error() != null && !off.error().isBlank());
			assertTrue(Files.exists(home.resolve(MobileAccess.OFF_MARKER)));
			assertThrows(IOException.class, () -> connect(phone.getHost(), oldPort));
			stillLocked.context().close();
			started.remove(stillLocked);
		} finally {
			setReadonly(settingsFile, false);
		}

		DesktopServer.Running recovered = startExisting(0);
		MobileAccess.Status recoveredStatus = mobile(recovered).status();
		assertFalse(recoveredStatus.enabled());
		assertFalse(recoveredStatus.active());
		assertTrue(recoveredStatus.error() == null || recoveredStatus.error().isBlank(), recoveredStatus.error());
		assertFalse(Files.exists(home.resolve(MobileAccess.OFF_MARKER)));

		String desktopCookie2 = openSession(recovered);
		assertEquals(200, send(recovered.baseUri().resolve("/api/desktop/mobile/access"), desktopCookie2, "PUT",
				"{\"enabled\":true}").statusCode());
		URI again = phoneBase(recovered);
		assertEquals(200, send(again.resolve("/api/illustrations"), cookie).statusCode(),
				"the revoked-by-close phone still has its device record");
	}

	private DesktopServer.Running start(boolean mobileEnabled, int preferredPort) {
		String address = mobileEnabled ? LanAddresses.siteLocal().stream().findFirst().orElse("") : "";
		return start(mobileEnabled, preferredPort, address);
	}

	private DesktopServer.Running start(boolean mobileEnabled, int preferredPort, String address) {
		String settings = "mobile.enabled=" + mobileEnabled + "\n"
				+ (address == null || address.isBlank() ? "" : "mobile.address=" + address + "\n");
		writeSettings(settings);
		return startExisting(preferredPort);
	}

	private void writeSettings(String content) {
		try {
			Files.createDirectories(home.resolve("config"));
			Files.writeString(home.resolve("config").resolve("settings.properties"), content, StandardCharsets.UTF_8);
		} catch (IOException failure) {
			throw new java.io.UncheckedIOException(failure);
		}
	}

	/** Starts without rewriting settings.properties, so a restart observes exactly what the previous run left behind. */
	private DesktopServer.Running startExisting(int preferredPort) {
		DesktopServer.Running running = DesktopServer.start(home, 0, true,
				Map.of(MobileAccess.LAN_PORT_PROPERTY, Integer.toString(preferredPort)));
		started.add(running);
		return running;
	}

	private static MobileAccess mobile(DesktopServer.Running running) {
		return running.context().getBean(MobileAccess.class);
	}

	private static URI phoneBase(DesktopServer.Running running) {
		MobileAccess.Status status = mobile(running).status();
		assertTrue(status.active(), status.error());
		assertNotNull(status.address());
		assertTrue(status.port() > 0);
		return URI.create("http://" + status.address() + ":" + status.port() + "/");
	}

	private static int lanConnectors(DesktopServer.Running running) {
		TomcatWebServer webServer = (TomcatWebServer) ((WebServerApplicationContext) running.context()).getWebServer();
		return webServer.getTomcat().getService().findConnectors().length;
	}

	private static void assumeLan() {
		Assumptions.assumeFalse(LanAddresses.siteLocal().isEmpty(), "a LAN address is needed for the phone connector");
	}

	private static void assumeWindowsReadonly() {
		Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
				"the Windows read-only attribute is the deterministic save-failure probe");
	}

	private static void setReadonly(Path file, boolean readonly) throws IOException {
		Files.setAttribute(file, "dos:readonly", readonly);
	}

	private static void connect(String address, int port) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, port), 3000);
		}
	}

	private String openSession(DesktopServer.Running running) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(running.sessionUri()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(302, response.statusCode());
		String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
		return setCookie.substring(0, setCookie.indexOf(';'));
	}

	private HttpResponse<String> send(URI uri, String cookie) throws Exception {
		return send(uri, cookie, "GET", null);
	}

	private HttpResponse<String> send(URI uri, String cookie, String method, String json) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
		if (cookie != null) builder.header("Cookie", cookie);
		if (json != null) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(json));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String rawGet(String connectAddress, int port, String host, String path, String cookie) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(connectAddress, port), 3000);
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n"
					+ (cookie == null ? "" : "Cookie: " + cookie + "\r\n")
					+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private long importPng(DesktopServer.Running running, String cookie, byte[] bytes) throws Exception {
		String boundary = "----mobile-access-test";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"red.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(bytes);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(running.baseUri().resolve("/api/illustrations/import"))
				.header("Cookie", cookie)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.body().contains("\"successCount\":1"), response.body());
		return running.context().getBean(JdbcTemplate.class).queryForObject("SELECT MAX(id) FROM asset", Long.class);
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
