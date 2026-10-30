package com.yuutara.illustrationarchive.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SplashScreen;

/**
 * The startup image the java launcher shows before any class loads ({@code -splash} in the packaged app). It only
 * says what is really happening; there is no progress bar because the time each stage takes is not known up front.
 * Without {@code -splash}, as when running from the JAR, there is no splash and every call does nothing.
 */
final class DesktopSplash {

	private static final Color PAPER = new Color(0xF6F6F5);
	private static final Color MUTED = new Color(0x8A8A86);
	private static final int LEFT = 41;
	private static final int BAND = 36;

	private DesktopSplash() {
	}

	static synchronized void status(String text) {
		SplashScreen splash = SplashScreen.getSplashScreen();
		if (splash == null) {
			return;
		}
		Dimension size = splash.getSize();
		Graphics2D graphics = splash.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
			graphics.setColor(PAPER);
			graphics.fillRect(LEFT, size.height - BAND, size.width - 2 * LEFT, BAND - 4);
			graphics.setColor(MUTED);
			graphics.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
			graphics.drawString(text, LEFT, size.height - 18);
		} finally {
			graphics.dispose();
		}
		splash.update();
	}

	static synchronized void close() {
		SplashScreen splash = SplashScreen.getSplashScreen();
		if (splash != null) {
			splash.close();
		}
	}
}
