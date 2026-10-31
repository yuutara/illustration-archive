package com.yuutara.illustrationarchive.desktop;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.awt.geom.RoundRectangle2D;

/**
 * The startup image the java launcher shows before any class loads ({@code -splash} in the packaged app). It says what
 * is really happening. The line under the text moves only toward the stage in progress and stops short of its cap,
 * because the time each stage takes is not known up front; it never reaches the end before the window appears.
 * Without {@code -splash}, as when running from the JAR, there is no splash and every call does nothing.
 *
 * <p>A background thread does the drawing. The first text drawn loads the fonts, which should not hold up startup.
 */
final class DesktopSplash {

	private static final Color MUTED = new Color(0x8E8E88);
	private static final Color ACCENT = new Color(0x93A6FF);
	private static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
	// Positions on the card in packaging/icon/splash.html, which has 24 px of margin around it.
	private static final int TEXT_LEFT = 60;
	private static final int TEXT_BASELINE = 258;
	private static final int TEXT_TOP = 242;
	private static final int TEXT_WIDTH = 220;
	private static final int TEXT_HEIGHT = 22;
	private static final int RAIL_LEFT = 60;
	private static final int RAIL_TOP = 276;
	private static final int RAIL_WIDTH = 388;
	private static final int RAIL_HEIGHT = 2;
	private static final long FRAME_MILLIS = 66;
	private static final double SETTLE_MILLIS = 1_200;

	private static String text = "";
	private static double cap;
	private static boolean drawing;

	private DesktopSplash() {
	}

	/** @param stageCap how far along the line this stage may go, between 0 and 1 */
	static synchronized void status(String stageText, double stageCap) {
		if (SplashScreen.getSplashScreen() == null) {
			return;
		}
		text = stageText;
		cap = stageCap;
		if (!drawing) {
			drawing = true;
			Thread painter = new Thread(DesktopSplash::draw, "desktop-splash");
			painter.setDaemon(true);
			painter.start();
		}
	}

	private static void draw() {
		double progress = 0;
		try {
			while (paint(progress)) {
				Thread.sleep(FRAME_MILLIS);
				progress = advance(progress, cap(), FRAME_MILLIS);
			}
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
		}
	}

	private static synchronized double cap() {
		return cap;
	}

	/** Eases toward the cap: quick at first, then slower, and it does not arrive. Never moves backward. */
	static double advance(double progress, double cap, long elapsedMillis) {
		return Math.max(progress, progress + (cap - progress) * (1 - Math.exp(-elapsedMillis / SETTLE_MILLIS)));
	}

	/** @return false once the splash has closed */
	private static synchronized boolean paint(double progress) {
		SplashScreen splash = SplashScreen.getSplashScreen();
		if (splash == null) {
			return false;
		}
		Graphics2D graphics = splash.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			// The overlay is drawn over the card, so clearing the old text shows the card again.
			graphics.setComposite(AlphaComposite.Clear);
			graphics.fillRect(TEXT_LEFT, TEXT_TOP, TEXT_WIDTH, TEXT_HEIGHT);
			graphics.setComposite(AlphaComposite.SrcOver);
			graphics.setColor(MUTED);
			graphics.setFont(FONT);
			graphics.drawString(text, TEXT_LEFT, TEXT_BASELINE);
			graphics.setColor(ACCENT);
			graphics.fill(new RoundRectangle2D.Double(RAIL_LEFT, RAIL_TOP, RAIL_WIDTH * progress, RAIL_HEIGHT, RAIL_HEIGHT, RAIL_HEIGHT));
		} finally {
			graphics.dispose();
		}
		splash.update();
		return true;
	}

	static synchronized void close() {
		SplashScreen splash = SplashScreen.getSplashScreen();
		if (splash != null) {
			splash.close();
		}
	}
}
