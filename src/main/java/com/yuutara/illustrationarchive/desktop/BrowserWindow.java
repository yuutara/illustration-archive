package com.yuutara.illustrationarchive.desktop;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fallback for machines without WebView2: opens the app through a Chromium browser that is already installed.
 * {@code --app} drops the tab strip and address bar; a dedicated profile keeps it apart from the user's everyday
 * browser.
 */
final class BrowserWindow {

	private static final List<String> LOCATIONS = List.of(
			"Microsoft\\Edge\\Application\\msedge.exe",
			"Google\\Chrome\\Application\\chrome.exe");
	private static final List<String> ROOTS = List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA");

	private final Path executable;
	private final Path profile;

	private BrowserWindow(Path executable, Path profile) {
		this.executable = executable;
		this.profile = profile;
	}

	static Optional<BrowserWindow> find(Path home, Map<String, String> environment) {
		for (String location : LOCATIONS) {
			for (String root : ROOTS) {
				String base = environment.get(root);
				if (base != null && Files.isRegularFile(Path.of(base, location))) {
					return Optional.of(new BrowserWindow(Path.of(base, location), home.resolve("window")));
				}
			}
		}
		return Optional.empty();
	}

	void open(URI uri, String manualProxy) throws IOException {
		new ProcessBuilder(command(uri, manualProxy, Files.exists(profile))).start();
	}

	/** Size is only set on first use: afterwards the profile remembers where the user put the window. */
	List<String> command(URI uri, String manualProxy, boolean profileExists) {
		// Without the last two, Edge signs the Windows account into this profile and asks about syncing it.
		List<String> command = new ArrayList<>(List.of(executable.toString(), "--app=" + uri,
				"--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check",
				"--disable-sync", "--disable-features=msImplicitSignin"));
		if (!profileExists) {
			command.add("--window-size=1440,900");
		}
		if (!manualProxy.isEmpty()) {
			// Inbox images load from X's CDN in this window, so they need the same route as the server.
			command.add("--proxy-server=" + manualProxy);
		}
		return command;
	}
}
