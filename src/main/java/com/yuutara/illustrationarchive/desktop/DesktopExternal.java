package com.yuutara.illustrationarchive.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * Hands things to Windows. The app window has its own WebView2 profile with no X login, so the X sign-in and
 * links out to X must open in the user's default browser instead.
 */
@Component
@Profile(DesktopServer.PROFILE)
public class DesktopExternal {

	private static final Logger log = LoggerFactory.getLogger(DesktopExternal.class);

	private final boolean enabled;

	public DesktopExternal(@Value("${illustration-archive.desktop.open-external:false}") boolean enabled) {
		this.enabled = enabled;
	}

	/** @return false when nothing opened; the page then offers the link to copy */
	public boolean browse(URI uri) {
		if (!enabled) return false;
		try {
			Desktop.getDesktop().browse(uri);
			return true;
		} catch (IOException | UnsupportedOperationException failure) {
			log.warn("Could not open the default browser.", failure);
			return false;
		}
	}

	public boolean open(Path folder) {
		if (!enabled) return false;
		try {
			Desktop.getDesktop().open(folder.toFile());
			return true;
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException failure) {
			log.warn("Could not open folder {}.", folder, failure);
			return false;
		}
	}
}
