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
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.internal.ole.win32.ICoreWebView2Controller;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The app's window: an SWT shell holding WebView2, owned by this JVM. WebView2 keeps its own profile in the data
 * folder and, unless Settings names a proxy, follows the Windows proxy settings by itself, so nothing here touches
 * the network.
 *
 * <p>The shell opens fully transparent and appears once the page reports its first screen, or shortly after the load
 * if it never does, which avoids a white flash and an empty wall. The splash image covers that time.
 *
 * <p>Closing follows the saved {@link CloseAction}. Asking happens in the page, so it looks like the rest of the app;
 * a page that cannot answer (the old gallery, a hung renderer) gets a plain Windows prompt, so the close button
 * always does something.
 */
final class DesktopWindow {

	private static final String TITLE = "插画档案";
	private static final String DATA_DIR_PROPERTY = "org.eclipse.swt.browser.EdgeDataDir";
	private static final String BROWSER_ARGS_PROPERTY = "org.eclipse.swt.browser.EdgeArgs";
	static final int[] ICON_SIZES = {16, 20, 24, 30, 32, 36, 40, 48, 64, 256};
	private static final String ASK_SCRIPT = "window.CClose && window.CClose.ask()";
	/** How long the page has to confirm it is asking before the window asks by itself. */
	private static final int PAGE_ASK_WAIT_MILLIS = 600;
	/** The load event comes before the works do, so the page says when its first screen is ready; this is how long it gets. */
	private static final int FIRST_SCREEN_WAIT_MILLIS = 700;
	/** Gives the page a moment to paint between saying it is ready and the window appearing. */
	private static final int PAINT_DELAY_MILLIS = 50;
	/** The splash stays a moment behind the window so nothing blinks between the two. */
	private static final int SPLASH_OVERLAP_MILLIS = 60;
	private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
	private static final int DWMWA_CAPTION_COLOR = 35;

	private final Display display;
	private final Shell shell;
	private final Browser browser;
	private final Path stateFile;
	private final Consumer<URI> openExternal;
	private final Supplier<CloseAction> closeAction;
	private final Runnable quit;
	private String appOrigin = "";
	private WindowState.Rect normalBounds;
	private boolean dark;
	private boolean revealed;
	private boolean hasTray;
	/** Moves on when the page takes the question, which cancels the window's own prompt for that close. */
	private int closeRequest;

	private DesktopWindow(Display display, Shell shell, Browser browser, Path stateFile, WindowState saved,
			WindowState.Rect bounds, Consumer<URI> openExternal, Supplier<CloseAction> closeAction, Runnable quit) {
		this.display = display;
		this.shell = shell;
		this.browser = browser;
		this.stateFile = stateFile;
		this.openExternal = openExternal;
		this.closeAction = closeAction;
		this.quit = quit;
		this.normalBounds = bounds;
		this.dark = saved.dark();
	}

	/** @return empty when WebView2 is not available, which the launcher answers with its fallback */
	static Optional<DesktopWindow> open(Display display, Path home, String manualProxy, Consumer<URI> openExternal,
			Supplier<CloseAction> closeAction, Runnable quit) {
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
		DesktopWindow window = new DesktopWindow(display, shell, browser, stateFile, saved, bounds, openExternal,
				closeAction, quit);
		window.paint();
		window.connect();
		return Optional.of(window);
	}

	private static List<WindowState.Rect> monitors(Display display) {
		return Arrays.stream(display.getMonitors()).map(Monitor::getClientArea).map(DesktopWindow::rect).toList();
	}

	private static WindowState.Rect rect(Rectangle area) {
		return new WindowState.Rect(area.x, area.y, area.width, area.height);
	}

	private static Image[] icons(Display display) {
		return IntStream.of(ICON_SIZES).mapToObj(size -> new Image(display, iconData(size))).toArray(Image[]::new);
	}

	static ImageData iconData(int size) {
		try (InputStream png = DesktopWindow.class.getResourceAsStream("/desktop/icon-" + size + ".png")) {
			return new ImageData(png);
		} catch (IOException unreadable) {
			throw new IllegalStateException("Cannot read the window icon.", unreadable);
		}
	}

	private void connect() {
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
				display.timerExec(FIRST_SCREEN_WAIT_MILLIS, DesktopWindow.this::reveal);
			}
		});
		shell.addListener(SWT.Resize, event -> rememberBounds());
		shell.addListener(SWT.Move, event -> rememberBounds());
		shell.addListener(SWT.Close, event -> {
			event.doit = false;
			closeRequested();
		});
	}

	private void closeRequested() {
		switch (hasTray ? closeAction.get() : CloseAction.EXIT) {
			case ASK -> ask();
			case TRAY -> toTray();
			case EXIT -> quit.run();
		}
	}

	private void ask() {
		if (shell.getMinimized()) {
			shell.setMinimized(false); // Closed from the taskbar: the question must be on screen.
		}
		int request = ++closeRequest;
		browser.execute(ASK_SCRIPT);
		display.timerExec(PAGE_ASK_WAIT_MILLIS, () -> {
			if (request == closeRequest && !shell.isDisposed()) {
				askNatively();
			}
		});
	}

	private void askNatively() {
		MessageBox box = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO | SWT.CANCEL);
		box.setText(TITLE);
		box.setMessage("要让" + TITLE + "留在系统托盘吗？\n\n是：最小化到托盘，继续在后台运行\n否：退出应用");
		switch (box.open()) {
			case SWT.YES -> toTray();
			case SWT.NO -> quit.run();
			default -> { }
		}
	}

	/** The state is saved here too: from the tray, the process may next end with Windows rather than through quit. */
	private void toTray() {
		saveState();
		shell.setVisible(false);
		setPageVisible(false);
	}

	/**
	 * SWT only ever tells WebView2 it is visible, so a hidden shell would go on rendering at full rate (measured: 180
	 * frames a second, and animated GIFs and video with it). The controller is not reachable through public API. If a
	 * later SWT moves it, the app still works and only keeps drawing in the background.
	 */
	private void setPageVisible(boolean visible) {
		try {
			Field engine = Browser.class.getDeclaredField("webBrowser");
			engine.setAccessible(true);
			Object edge = engine.get(browser);
			Field controller = edge.getClass().getDeclaredField("controller");
			controller.setAccessible(true);
			((ICoreWebView2Controller) controller.get(edge)).put_IsVisible(visible);
		} catch (ReflectiveOperationException | RuntimeException unreachable) {
			unreachable.printStackTrace();
		}
	}

	private void handle(String action, String value) {
		switch (action) {
			case "theme" -> {
				dark = "dark".equals(value);
				paint();
			}
			case "ready" -> display.timerExec(PAINT_DELAY_MILLIS, this::reveal);
			case "open" -> {
				if (LinkPolicy.of(value, appOrigin) == LinkPolicy.EXTERNAL) {
					openExternal.accept(URI.create(value));
				}
			}
			case "close" -> {
				closeRequest++;
				if ("tray".equals(value)) {
					toTray();
				} else if ("exit".equals(value)) {
					quit.run();
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

	Shell shell() {
		return shell;
	}

	/**
	 * Shows a page again after the server restarted. Going to the address the window already shows, or one that only
	 * adds a fragment, would at most scroll, and the page would keep what it showed before; that case reloads.
	 */
	void reload(URI page) {
		String current = browser.getUrl();
		String target = page.toString();
		if (withoutFragment(current).equals(withoutFragment(target))) {
			browser.refresh();
		} else {
			load(page);
		}
	}

	private static String withoutFragment(String address) {
		int fragment = address.indexOf('#');
		return fragment < 0 ? address : address.substring(0, fragment);
	}

	/** Opens a page of the app; the first call carries the session token, later ones may go to any of its pages. */
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
		// Not before now: the icon's first click must find a window worth showing.
		hasTray = DesktopTray.install(shell, TITLE, this::activate, quit);
		display.timerExec(SPLASH_OVERLAP_MILLIS, DesktopSplash::close);
	}

	void activate() {
		if (!shell.getVisible()) {
			setPageVisible(true);
			shell.setVisible(true);
		}
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

	void saveState() {
		try {
			new WindowState(normalBounds, shell.getMaximized(), dark).save(stateFile);
		} catch (IOException unsaved) {
			// The next launch simply starts from the default place.
			unsaved.printStackTrace();
		}
	}
}
