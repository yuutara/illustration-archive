package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopShellTest {

	@TempDir
	Path base;

	private Path install(String location) throws IOException {
		Path executable = base.resolve(location);
		Files.createDirectories(executable.getParent());
		Files.createFile(executable);
		return executable;
	}

	@Test
	void prefersEdgeThenChromeAndReportsNeither() throws IOException {
		Path home = base.resolve("home");
		Map<String, String> environment = Map.of("ProgramFiles", base.toString());
		assertEquals(Optional.empty(), BrowserWindow.find(home, environment));

		Path chrome = install("Google\\Chrome\\Application\\chrome.exe");
		assertEquals(chrome.toString(), BrowserWindow.find(home, environment).orElseThrow()
				.command(URI.create("http://127.0.0.1:1/"), "", true).get(0));

		Path edge = install("Microsoft\\Edge\\Application\\msedge.exe");
		assertEquals(edge.toString(), BrowserWindow.find(home, environment).orElseThrow()
				.command(URI.create("http://127.0.0.1:1/"), "", true).get(0));
	}

	@Test
	void theWindowHasItsOwnProfileAndOnlyTheFirstRunSetsItsSize() throws IOException {
		install("Microsoft\\Edge\\Application\\msedge.exe");
		Path home = base.resolve("home");
		BrowserWindow window = BrowserWindow.find(home, Map.of("ProgramFiles", base.toString())).orElseThrow();
		URI session = URI.create("http://127.0.0.1:47391/desktop/session?token=abc");

		List<String> first = window.command(session, "", false);
		assertTrue(first.contains("--app=" + session));
		assertTrue(first.contains("--user-data-dir=" + home.resolve("window")));
		assertTrue(first.contains("--window-size=1440,900"));
		assertTrue(first.contains("--disable-sync") && first.contains("--disable-features=msImplicitSignin"),
				"the app window must not sign in or ask to sync the Windows account");

		List<String> later = window.command(session, "127.0.0.1:7897", true);
		assertFalse(later.contains("--window-size=1440,900"), "the profile remembers where the user put the window");
		assertTrue(later.contains("--proxy-server=127.0.0.1:7897"));
	}

	@Test
	void theWindowOnlyShowsTheAppsOwnPages() {
		String app = "http://127.0.0.1:47391";
		assertEquals(LinkPolicy.STAY, LinkPolicy.of(app + "/c/x.html", app));
		assertEquals(LinkPolicy.STAY, LinkPolicy.of("about:blank", app));
		assertEquals(LinkPolicy.EXTERNAL, LinkPolicy.of("https://x.com/a/status/1", app));
		assertEquals(LinkPolicy.EXTERNAL, LinkPolicy.of("http://127.0.0.1:47391@example.com/", app), "not the app, despite how it starts");
		assertEquals(LinkPolicy.EXTERNAL, LinkPolicy.of("http://127.0.0.1:47392/", app), "another local port is not the app");
		assertEquals(LinkPolicy.BLOCK, LinkPolicy.of("file:///C:/Users/me/picture.png", app));
		assertEquals(LinkPolicy.BLOCK, LinkPolicy.of("javascript:alert(1)", app));
		assertEquals(LinkPolicy.BLOCK, LinkPolicy.of(null, app));
	}

	@Test
	void theWindowReturnsToItsPlaceAndThemeAndTheFileIsOptional() throws IOException {
		Path file = base.resolve("config").resolve("window.properties");
		WindowState.Rect screen = new WindowState.Rect(0, 0, 2560, 1400);
		WindowState firstRun = WindowState.load(file);
		assertFalse(firstRun.dark());
		assertEquals(new WindowState.Rect(560, 250, 1440, 900), firstRun.placedOn(List.of(screen), screen));

		WindowState.Rect place = new WindowState.Rect(100, 80, 1200, 800);
		new WindowState(place, true, true).save(file);
		WindowState saved = WindowState.load(file);
		assertTrue(saved.maximized() && saved.dark());
		assertEquals(place, saved.placedOn(List.of(screen), screen));

		Files.writeString(file, "width=not-a-number");
		assertEquals(firstRun, WindowState.load(file), "a damaged file only costs the default place");
	}

	@Test
	void aWindowLeftOnAMissingMonitorComesBackAndAFirstRunFitsSmallScreens() {
		WindowState.Rect laptop = new WindowState.Rect(0, 0, 1366, 728);
		WindowState onUnpluggedMonitor = new WindowState(new WindowState.Rect(3000, 100, 1200, 800), false, false);
		assertEquals(new WindowState.Rect(0, 0, 1366, 728), onUnpluggedMonitor.placedOn(List.of(laptop), laptop));

		WindowState.Rect secondMonitor = new WindowState.Rect(1366, 0, 1920, 1040);
		assertEquals(onUnpluggedMonitor.bounds(), new WindowState(new WindowState.Rect(3000, 100, 1200, 800), false, false)
				.placedOn(List.of(laptop, secondMonitor), laptop));
		WindowState tooSmall = new WindowState(new WindowState.Rect(10, 10, 200, 100), false, false);
		assertEquals(new WindowState.Rect(0, 0, 1366, 728), tooSmall.placedOn(List.of(laptop), laptop));
	}

	@Test
	void theSplashLineMovesForwardAndStopsShortOfItsStage() {
		double progress = 0;
		for (int frame = 0; frame < 2_000; frame++) {
			double next = DesktopSplash.advance(progress, 0.7, 66);
			assertTrue(next >= progress && next <= 0.7, "frame " + frame);
			progress = next;
		}
		assertTrue(progress > 0.69, "a slow stage still gets close to its cap");
		assertEquals(0.5, DesktopSplash.advance(0.5, 0.3, 66), "a later stage never moves the line back");
		assertTrue(DesktopSplash.advance(0.69, 0.92, 66) > 0.69, "the next stage picks up from where the line is");
	}

	@Test
	void theLauncherReadsTheManualProxyBeforeSpringStarts() throws IOException {
		assertEquals("", DesktopSettings.savedProxy(base));
		Files.createDirectories(base.resolve("config"));
		Files.writeString(base.resolve("config").resolve("settings.properties"), "proxy.address=127.0.0.1\\:1080\n");
		assertEquals("127.0.0.1:1080", DesktopSettings.savedProxy(base));
	}

	@Test
	void anEarlierLibraryStaysWhereItIsAndANewOneGetsTheProductName() throws IOException {
		assertEquals(base.resolve("IllustrationArchive"), DesktopServer.homeUnder(base));

		Files.createDirectories(base.resolve("IllustrationArchivePoC"));
		assertEquals(base.resolve("IllustrationArchivePoC"), DesktopServer.homeUnder(base));

		Files.createDirectories(base.resolve("IllustrationArchive"));
		assertEquals(base.resolve("IllustrationArchive"), DesktopServer.homeUnder(base),
				"a library created by the migration tool takes over once it exists");
	}

	@Test
	void anUnknownSavedCloseChoiceFallsBackToAsking() {
		assertEquals(CloseAction.TRAY, CloseAction.stored("tray"));
		assertEquals(CloseAction.EXIT, CloseAction.stored("exit"));
		assertEquals(CloseAction.ASK, CloseAction.stored("ask"));
		assertEquals(CloseAction.ASK, CloseAction.stored(null));
		assertEquals(CloseAction.ASK, CloseAction.stored("minimize"), "a future or damaged value only costs the question");
	}

	@Test
	void theTrayUsesTheSizeWindowsDrawsAndNeverScalesAnIconUp() {
		assertEquals(16, DesktopTray.iconSize(16));
		assertEquals(20, DesktopTray.iconSize(20), "125 %");
		assertEquals(24, DesktopTray.iconSize(24), "150 %");
		assertEquals(32, DesktopTray.iconSize(32), "200 %");
		assertEquals(30, DesktopTray.iconSize(28), "between sizes it picks the next larger one");
		assertEquals(256, DesktopTray.iconSize(512));
	}

	@Test
	void aManualProxyNeverAppliesToLoopback() {
		DesktopProxySelector selector = DesktopProxySelector.installed();
		try {
			selector.useManual(InetSocketAddress.createUnresolved("127.0.0.1", 7897));
			assertEquals(Proxy.Type.HTTP, ProxySelector.getDefault().select(URI.create("https://api.x.com/")).get(0).type());
			assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(URI.create("http://localhost:47392/x/callback")).get(0));
		} finally {
			selector.useManual(null);
		}
	}
}
