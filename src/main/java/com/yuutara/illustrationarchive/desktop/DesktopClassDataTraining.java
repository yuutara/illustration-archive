package com.yuutara.illustrationarchive.desktop;

import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Packaging step, not a feature: runs the server once in an empty temporary library and touches what a launch uses,
 * so that the JVM, started by build-desktop.ps1 with {@code -XX:ArchiveClassesAtExit}, can save those classes. The app
 * then starts in about half the time. The archive is made here and only read at run time because recording it is
 * slow: left to a first launch, that launch would take a minute. It makes no network calls beyond this loopback port.
 */
public final class DesktopClassDataTraining {

	public static final String COMMAND = "--train-class-data";
	private static final List<String> PAGES = List.of("/c/", "/c/x.html", "/c/settings.html", "/ui-capabilities.js",
			"/api/illustrations?page=0&size=48", "/api/x-import/inbox?page=0&size=40", "/api/x-auth/status", "/api/desktop/settings");

	private DesktopClassDataTraining() {
	}

	public static void run() throws IOException {
		Path home = Files.createTempDirectory("illustration-archive-training");
		// No log file: the logging system would keep it open and the folder could not be removed.
		DesktopServer.Running running = DesktopServer.start(home, 0, true, Map.of("logging.file.name", ""));
		try {
			for (String page : PAGES) {
				read(running, page);
			}
		} finally {
			running.context().close();
			FileSystemUtils.deleteRecursively(home);
		}
	}

	private static void read(DesktopServer.Running running, String page) throws IOException {
		URI address = running.baseUri().resolve(page);
		HttpURLConnection request = (HttpURLConnection) address.toURL().openConnection(Proxy.NO_PROXY);
		request.setRequestProperty("Cookie", LocalAccessFilter.COOKIE_NAME + "=" + running.accessToken());
		if (request.getResponseCode() != HttpURLConnection.HTTP_OK) {
			throw new IOException(page + " answered " + request.getResponseCode());
		}
		try (InputStream body = request.getInputStream()) {
			body.transferTo(java.io.OutputStream.nullOutputStream());
		}
	}
}
