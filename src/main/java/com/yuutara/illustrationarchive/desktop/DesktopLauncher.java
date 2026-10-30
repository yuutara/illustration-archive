package com.yuutara.illustrationarchive.desktop;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Starts the app: the splash image and a hidden window while the server comes up, then the window appears. This
 * thread runs the window's event loop, and closing the window ends it: the Spring context closes, so requests drain,
 * the connection pool closes (and with it the H2 file), and the port is released before the process ends.
 */
public final class DesktopLauncher {

	/** Stable port keeps the page's preferences (theme, layout) between launches; falls back if busy. */
	static final int PREFERRED_PORT = 47391;
	/** Windows groups the app's windows and taskbar entry under this. */
	private static final String APP_ID = "IllustrationArchive";
	private static final int ACTIVATE_TIMEOUT_MILLIS = 2_000;
	private static final int REVEAL_ANYWAY_MILLIS = 15_000;

	private final Path home;
	private final FileLock instanceLock;
	private final Display display;
	private volatile DesktopServer.Running running;
	private volatile boolean stopping;
	private DesktopWindow window;
	private DesktopControls controls;

	private DesktopLauncher(Path home, FileLock instanceLock, Display display) {
		this.home = home;
		this.instanceLock = instanceLock;
		this.display = display;
	}

	public static void main(String[] args) {
		// Must be set before anything asks for the default ProxySelector, which reads it once.
		System.setProperty("java.net.useSystemProxies", "true");
		Path home = DesktopServer.defaultHome();
		FileLock lock;
		try {
			Files.createDirectories(home);
			lock = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
					.tryLock();
		} catch (IOException exception) {
			showMessage(SWT.ICON_ERROR, "无法使用数据目录：" + home + "\n" + exception.getMessage());
			return;
		}
		if (lock == null) {
			showExisting(home);
			return;
		}
		Display.setAppName(APP_ID);
		new DesktopLauncher(home, lock, new Display()).run();
	}

	/** A second launch means "show me the app"; the running instance left its entry address for this. */
	private static void showExisting(Path home) {
		Path entry = entryFile(home);
		try {
			if (Files.exists(entry)) {
				askToShow(URI.create(Files.readString(entry).trim()));
			}
			// With no entry the app is still starting, and its window will appear by itself.
		} catch (IOException | IllegalArgumentException unavailable) {
			showMessage(SWT.ICON_INFORMATION, "插画档案已在运行。\n请在任务栏中找到已打开的窗口。");
		}
		DesktopSplash.close();
	}

	private static void askToShow(URI session) throws IOException {
		// Direct on purpose: this is the app's own loopback port, which must never go through a proxy.
		HttpURLConnection request = (HttpURLConnection) session.resolve("/api/desktop/show").toURL().openConnection(Proxy.NO_PROXY);
		request.setRequestMethod("POST");
		request.setConnectTimeout(ACTIVATE_TIMEOUT_MILLIS);
		request.setReadTimeout(ACTIVATE_TIMEOUT_MILLIS);
		request.setRequestProperty("Cookie", LocalAccessFilter.COOKIE_NAME + "=" + session.getQuery().substring("token=".length()));
		if (request.getResponseCode() >= 300) {
			throw new IOException("The running app answered " + request.getResponseCode());
		}
	}

	private static Path entryFile(Path home) {
		return home.resolve("window").resolve("entry.txt");
	}

	private void run() {
		DesktopSplash.status("正在读取图库…");
		window = DesktopWindow.open(display, home, DesktopSettings.savedProxy(home), this::openInDefaultBrowser, this::stop)
				.orElse(null);
		if (window == null) {
			DesktopSplash.close();
			controls = new DesktopControls(display, home, this::openInBrowser, this::stop);
		}
		// Created from the main thread so Spring sees the application's class loader.
		ClassLoader loader = DesktopLauncher.class.getClassLoader();
		Thread starter = new Thread(() -> {
			Thread.currentThread().setContextClassLoader(loader);
			startServer();
		}, "desktop-start");
		starter.start();

		while (!stopping) {
			if (!display.readAndDispatch()) {
				display.sleep();
			}
		}
		shutDown();
	}

	private void startServer() {
		try {
			DesktopServer.Running started = DesktopServer.start(home, choosePort(), false);
			if (stopping) {
				started.context().close();
				return;
			}
			running = started;
			started.context().getBean(DesktopActivation.class).onShow(() -> display.asyncExec(this::activate));
			rememberEntry(started);
			display.asyncExec(() -> show(started));
		} catch (RuntimeException exception) {
			Throwable cause = exception;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			String message = cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
			display.asyncExec(() -> startFailed(message));
		}
	}

	private void show(DesktopServer.Running started) {
		if (window != null) {
			DesktopSplash.status("正在打开窗口…");
			window.load(started.sessionUri());
			display.timerExec(REVEAL_ANYWAY_MILLIS, window::reveal);
		} else {
			controls.ready(started.baseUri().toString());
			openInBrowser();
		}
	}

	private void startFailed(String message) {
		DesktopSplash.close();
		showMessage(SWT.ICON_ERROR, "启动失败：\n" + message + "\n\n详见日志：" + home.resolve("logs").resolve("desktop.log"));
		stop();
	}

	private void activate() {
		if (window != null) {
			window.activate();
		} else {
			controls.activate();
		}
	}

	private void stop() {
		stopping = true;
		display.wake();
	}

	private void openInDefaultBrowser(URI address) {
		DesktopServer.Running current = running;
		if (current != null) {
			// Off the UI thread: the page that asked is waiting on it, and the browser may take a while to start.
			new Thread(() -> current.context().getBean(DesktopExternal.class).browse(address), "desktop-external").start();
		}
	}

	/** The fallback for machines without WebView2: the old app window if a Chromium is installed, or any browser. */
	private void openInBrowser() {
		DesktopServer.Running current = running;
		if (current == null) {
			return;
		}
		Optional<BrowserWindow> browserWindow = BrowserWindow.find(home, System.getenv());
		if (browserWindow.isPresent()) {
			try {
				browserWindow.get().open(current.sessionUri(), current.context().getBean(DesktopSettings.class).proxy());
				return;
			} catch (IOException exception) {
				exception.printStackTrace();
			}
		}
		openInDefaultBrowser(current.sessionUri());
	}

	private void rememberEntry(DesktopServer.Running started) {
		try {
			Files.createDirectories(entryFile(home).getParent());
			Files.writeString(entryFile(home), started.sessionUri().toString());
		} catch (IOException exception) {
			// Only a second launch needs this, and it degrades to a message.
			exception.printStackTrace();
		}
	}

	private static int choosePort() {
		try (ServerSocket probe = new ServerSocket(PREFERRED_PORT, 1, InetAddress.getLoopbackAddress())) {
			return PREFERRED_PORT;
		} catch (IOException busy) {
			return 0;
		}
	}

	private void shutDown() {
		int code = 0;
		try {
			display.dispose();
			Files.deleteIfExists(entryFile(home));
			DesktopServer.Running current = running;
			if (current != null) {
				current.context().close();
			}
		} catch (IOException | RuntimeException exception) {
			code = 1;
			exception.printStackTrace();
		} finally {
			try {
				instanceLock.release();
				instanceLock.channel().close();
			} catch (IOException ignored) {
				// The OS releases the lock when the process exits anyway.
			}
		}
		System.exit(code);
	}

	private static void showMessage(int icon, String message) {
		Display display = Display.getCurrent() == null ? new Display() : Display.getCurrent();
		MessageBox box = new MessageBox(new Shell(display), icon | SWT.OK);
		box.setText("插画档案");
		box.setMessage(message);
		box.open();
	}
}
