package com.yuutara.illustrationarchive.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Where the window was and which theme the page last used, in {@code <home>\config\window.properties}. Remembering
 * the theme here is what lets the next launch paint the frame in the right color before any page exists. It only
 * costs a default window if lost, so a missing or unreadable file is not an error.
 */
record WindowState(Rect bounds, boolean maximized, boolean dark) {

	static final int DEFAULT_WIDTH = 1440;
	static final int DEFAULT_HEIGHT = 900;
	static final int MIN_WIDTH = 960;
	static final int MIN_HEIGHT = 600;
	private static final WindowState FIRST_RUN = new WindowState(null, false, false);
	/** Of the title bar: this much must be on a monitor for the window to be reachable with the mouse. */
	private static final int GRAB_MARGIN = 80;

	record Rect(int x, int y, int width, int height) {

		private boolean contains(int pointX, int pointY) {
			return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
		}
	}

	static WindowState load(Path file) {
		Properties values = new Properties();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			values.load(reader);
			Rect bounds = values.containsKey("width") ? new Rect(number(values, "x"), number(values, "y"),
					number(values, "width"), number(values, "height")) : null;
			return new WindowState(bounds, Boolean.parseBoolean(values.getProperty("maximized")),
					Boolean.parseBoolean(values.getProperty("dark")));
		} catch (IOException | IllegalArgumentException unusable) {
			return FIRST_RUN;
		}
	}

	private static int number(Properties values, String key) {
		return Integer.parseInt(values.getProperty(key, ""));
	}

	void save(Path file) throws IOException {
		Properties values = new Properties();
		if (bounds != null) {
			values.setProperty("x", Integer.toString(bounds.x()));
			values.setProperty("y", Integer.toString(bounds.y()));
			values.setProperty("width", Integer.toString(bounds.width()));
			values.setProperty("height", Integer.toString(bounds.height()));
		}
		values.setProperty("maximized", Boolean.toString(maximized));
		values.setProperty("dark", Boolean.toString(dark));
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			values.store(writer, "Illustration Archive window");
		}
	}

	/** The saved place if it is still reachable (a monitor may have gone away), otherwise centered on the primary one. */
	Rect placedOn(List<Rect> monitors, Rect primary) {
		if (bounds != null && bounds.width() >= MIN_WIDTH && bounds.height() >= MIN_HEIGHT && reachable(bounds, monitors)) {
			return bounds;
		}
		int width = Math.min(DEFAULT_WIDTH, primary.width());
		int height = Math.min(DEFAULT_HEIGHT, primary.height());
		return new Rect(primary.x() + (primary.width() - width) / 2, primary.y() + (primary.height() - height) / 2, width, height);
	}

	private static boolean reachable(Rect window, List<Rect> monitors) {
		int titleY = window.y() + GRAB_MARGIN / 2;
		return monitors.stream().anyMatch(monitor -> monitor.contains(window.x() + GRAB_MARGIN, titleY)
				|| monitor.contains(window.x() + window.width() - GRAB_MARGIN, titleY));
	}
}
