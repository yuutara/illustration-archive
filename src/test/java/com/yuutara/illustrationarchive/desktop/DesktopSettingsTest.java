package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DesktopSettingsTest {

	@Test
	void aReadLimitEditedByHandIntoSomethingSettingsNeverOffersFallsBackToTheDefault(@TempDir Path home) throws Exception {
		assertEquals(50, new DesktopSettings(home.toString()).reads(), "nothing saved yet");
		Files.createDirectories(home.resolve("config"));
		for (String value : new String[] {"999", "abc", "", "-25"}) {
			Files.writeString(home.resolve("config").resolve("settings.properties"), "x.sync.read-limit=" + value + "\n");
			assertEquals(50, new DesktopSettings(home.toString()).reads(), value);
		}
		Files.writeString(home.resolve("config").resolve("settings.properties"), "x.sync.read-limit=200\n");
		assertEquals(200, new DesktopSettings(home.toString()).reads());
	}

	@Test
	void onlyTheOfferedChoicesCanBeSaved(@TempDir Path home) {
		DesktopSettings settings = new DesktopSettings(home.toString());
		assertThrows(IllegalArgumentException.class, () -> settings.saveReads(30));
		assertEquals(50, settings.reads());
		settings.saveReads(100);
		assertEquals(100, new DesktopSettings(home.toString()).reads());
	}
}
