package com.yuutara.illustrationarchive.desktop;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * The app's window: an SWT shell holding WebView2, owned by this JVM. WebView2 keeps its own profile in the data
 * folder and, unless Settings names a proxy, follows the Windows proxy settings by itself, so nothing here touches
 * the network.
 *
 * <p>The shell opens fully transparent and appears once the first page has loaded, which avoids a white flash. The
 * splash image covers that time.
 */
final class DesktopWindow {

	private static final String TITLE = "插画档案";
	private static final String DATA_DIR_PROPERTY = "org.eclipse.swt.browser.EdgeDataDir";
	private static final String BROWSER_ARGS_PROPERTY = "org.eclipse.swt.browser.EdgeArgs";
	private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 256};
	/** Gives the page a moment to paint between the load finishing and the window appearing. */
	private static final int REVEAL_DELAY_MILLIS = 80;
	private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
	private static final int DWMWA_CAPTION_COLOR = 35;

	private final Display display;
	private final Shell shell;
	private final Browser browser;
	private final Path stateFile;
	private final Consumer<URI> openExternal;
	private String appOrigin = "";
	private WindowState.Rect normalBounds;
	private boolean dark;
	private boolean revealed;

	private DesktopWindow(Display display, Shell shell, Browser browser, Path stateFile, WindowState saved,
			WindowState.Rect bounds, Consumer<URI> openExternal) {
		this.display = display;
		this.shell = shell;
		this.browser = browser;
		this.stateFile = stateFile;
		this.openExternal = openExternal;
		this.normalBounds = bounds;
		this.dark = saved.dark();
	}

	/** @return empty when WebView2 is not available, which the launcher answers with its fallback */
	static Optional<DesktopWindow> open(Display display, Path home, String manualProxy, Consumer<URI> openExternal,
			Runnable closed) {
		System.setProperty(DATA_DIR_PROPERTY, home.resolve("webview2").toString());
		if (!manualProxy.isEmpty()) {
			System.setProperty(BROWSER_ARGS_PROPERTY, "--proxy-server=" + manualProxy);
		}
		Path stateFile = home.resolve("config").resolve("window.properties");
		WindowState saved = WindowState.load(stateFile);
		WindowState.Rect bounds = saved.placedOn(monitors(display), rect(display.getPrimaryMonitor().getClientArea()));

		Shell shell = new Shell(display, SWT.SHELL_TRIM);
		shell.setText(TITLE);
		shell.setImages(icons(display));
		shell.setLayout(new FillLayout());
		shell.setMinimumSize(WindowState.MIN_WIDTH, WindowState.MIN_HEIGHT);
		shell.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
		shell.setMaximized(saved.maximized());
		shell.setAlpha(0);
		shell.open();
		Browser browser;
		try {
			browser = new Browser(shell, SWT.EDGE);
		} catch (SWTError unavailable) {
			unavailable.printStackTrace();
			shell.dispose();
			return Optional.empty();
		}
		shell.layout();
		DesktopWindow window = new DesktopWindow(display, shell, browser, stateFile, saved, bounds, openExternal);
		window.paint();
		window.connect(closed);
		return Optional.of(window);
	}

	private static List<WindowState.Rect> monitors(Display display) {
		return Arrays.stream(display.getMonitors()).map(Monitor::getClientArea).map(DesktopWindow::rect).toList();
	}

	private static WindowState.Rect rect(Rectangle area) {
		return new WindowState.Rect(area.x, area.y, area.width, area.height);
	}

	private static Image[] icons(Display display) {
		return IntStream.of(ICON_SIZES).mapToObj(size -> {
			try (InputStream png = DesktopWindow.class.getResourceAsStream("/desktop/icon-" + size + ".png")) {
				return new Image(display, png);
			} catch (IOException unreadable) {
				throw new IllegalStateException("Cannot read the window icon.", unreadable);
			}
		}).toArray(Image[]::new);
	}

	private void connect(Runnable closed) {
		new BrowserFunction(browser, "iaDesktop") {
			@Override
			public Object function(Object[] arguments) {
				if (arguments.length == 2 && arguments[0] instanceof String action && arguments[1] instanceof String value) {
					handle(action, value);
				}
				return null;
			}
		};
		browser.addLocationListener(new LocationAdapter() {
			@Override
			public void changing(LocationEvent event) {
				LinkPolicy policy = LinkPolicy.of(event.location, appOrigin);
				event.doit = policy == LinkPolicy.STAY;
				if (policy == LinkPolicy.EXTERNAL) {
					openExternal.accept(URI.create(event.location));
				}
			}
		});
		// Links that ask for a new window are sent to the default browser by the page, so WebView2 must not open one.
		browser.addOpenWindowListener(event -> event.required = true);
		browser.addProgressListener(new ProgressAdapter() {
			@Override
			public void completed(ProgressEvent event) {
				display.timerExec(REVEAL_DELAY_MILLIS, DesktopWindow.this::reveal);
			}
		});
		shell.addListener(SWT.Resize, event -> rememberBounds());
		shell.addListener(SWT.Move, event -> rememberBounds());
		shell.addListener(SWT.Close, event -> {
			saveState();
			closed.run();
		});
	}

	private void handle(String action, String value) {
		switch (action) {
			case "theme" -> {
				dark = "dark".equals(value);
				paint();
			}
			case "open" -> {
				if (LinkPolicy.of(value, appOrigin) == LinkPolicy.EXTERNAL) {
					openExternal.accept(URI.create(value));
				}
			}
			default -> { }
		}
	}

	/** Matches the frame to the page's theme. Windows 10 ignores the caption color and still honors dark mode. */
	private void paint() {
		int red = dark ? 0x11 : 0xF6;
		int green = dark ? 0x11 : 0xF6;
		int blue = dark ? 0x11 : 0xF5;
		shell.setBackground(new Color(display, red, green, blue));
		browser.setBackground(shell.getBackground());
		OS.DwmSetWindowAttribute(shell.handle, DWMWA_USE_IMMERSIVE_DARK_MODE, new int[] {dark ? 1 : 0}, Integer.BYTES);
		OS.DwmSetWindowAttribute(shell.handle, DWMWA_CAPTION_COLOR, new int[] {blue << 16 | green << 8 | red}, Integer.BYTES);
	}

	void load(URI session) {
		appOrigin = session.getScheme() + "://" + session.getAuthority();
		browser.setUrl(session.toString());
	}

	void reveal() {
		if (revealed || shell.isDisposed()) {
			return;
		}
		revealed = true;
		shell.setAlpha(255);
		shell.forceActive();
		browser.setFocus();
		DesktopSplash.close();
	}

	void activate() {
		if (shell.getMinimized()) {
			shell.setMinimized(false);
		}
		reveal();
		shell.forceActive();
	}

	private void rememberBounds() {
		if (revealed && !shell.getMaximized() && !shell.getMinimized()) {
			normalBounds = rect(shell.getBounds());
		}
	}

	private void saveState() {
		try {
			new WindowState(normalBounds, shell.getMaximized(), dark).save(stateFile);
		} catch (IOException unsaved) {
			// The next launch simply starts from the default place.
			unsaved.printStackTrace();
		}
	}
}
