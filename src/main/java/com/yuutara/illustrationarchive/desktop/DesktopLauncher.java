package com.yuutara.illustrationarchive.desktop;

import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.springframework.jdbc.core.JdbcTemplate;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Starts the app: the splash image and a hidden window while the server comes up, then the window appears. This
 * thread runs the window's event loop, and quitting ends it, whether that comes from the window, the tray or Windows
 * ending the session: the tray icon and WebView2 go first, then the Spring context closes, so requests drain, the
 * connection pool closes (and with it the H2 file), and the port is released before the process ends.
 *
 * <p>The launcher also owns backups, restores and imports from the old app ({@link LibraryMaintenance}), which stop
 * and start the server while
 * the window stays: the server comes back on the same token, so the window's session stays valid. Before the server
 * first starts, a restore that was interrupted is undone; a library that does not open can be restored from a backup.
 */
public final class DesktopLauncher {

	/** Stable port keeps the page's preferences (theme, layout) between launches; falls back if busy. */
	static final int PREFERRED_PORT = 47391;
	/** Windows groups the app's windows and taskbar entry under this. */
	private static final String APP_ID = "IllustrationArchive";
	private static final int ACTIVATE_TIMEOUT_MILLIS = 2_000;
	private static final int REVEAL_ANYWAY_MILLIS = 15_000;
	private static final double SERVER_STAGE_CAP = 0.7;
	private static final double PAGE_STAGE_CAP = 0.92;
	private static final int CHECK_POLL_MILLIS = 200;

	private final Path home;
	private final FileLock instanceLock;
	private final Display display;
	private final String accessToken = DesktopServer.newAccessToken();
	private final LibraryMaintenance maintenance;
	private volatile DesktopServer.Running running;
	private volatile int port = PREFERRED_PORT;
	private volatile boolean stopping;
	/** Reads, backs up and starts the library off the UI thread; quitting waits for it. */
	private final LibraryStart starter = new LibraryStart(new StartSteps(), () -> stopping);
	private DesktopWindow window;
	private DesktopControls controls;
	/** The app's pages have been opened once; until then a server that comes up still has to show the window. */
	private boolean shown;
	private boolean quitAfterMaintenance;
	private MaintenanceWindow maintenanceWindow;
	private String startFailure;

	private DesktopLauncher(Path home, FileLock instanceLock, Display display) {
		this.home = home;
		this.instanceLock = instanceLock;
		this.display = display;
		this.maintenance = new LibraryMaintenance(home, new Server(), new Dialogs(), new Screen(), appVersion());
	}

	static String appVersion() {
		// jpackage writes the release number here; the jar's manifest only has Maven's version.
		return System.getProperty("jpackage.app-version", "开发版");
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
		// Before anything opens the database. Where the record and the folders disagree, nothing starts at all.
		try {
			String recovered = LibraryRestore.recover(home);
			if (recovered != null) {
				DesktopSplash.close();
				showMessage(SWT.ICON_WARNING, recovered);
			}
		} catch (MaintenanceException stuck) {
			DesktopSplash.close();
			showMessage(SWT.ICON_ERROR, stuck.getMessage());
			shutDown();
			return;
		}
		DesktopSplash.status("正在准备图库…", SERVER_STAGE_CAP);
		// Windows asks this before a sign-out or shutdown; a window in the tray would otherwise die mid-run.
		display.addListener(SWT.Close, event -> stop());
		window = DesktopWindow.open(display, home, DesktopSettings.savedProxy(home), this::openInDefaultBrowser,
				this::closeAction, this::stop).orElse(null);
		if (window == null) {
			DesktopSplash.close();
			controls = new DesktopControls(display, home, this::openInBrowser, this::stop);
		}
		startInBackground(true);

		while (!stopping) {
			if (!display.readAndDispatch()) {
				display.sleep();
			}
		}
		shutDown();
	}

	/** @param backUpFirst back the library up first if this version will upgrade it */
	private void startInBackground(boolean backUpFirst) {
		// Created from the main thread so Spring sees the application's class loader.
		starter.begin(backUpFirst, DesktopLauncher.class.getClassLoader());
	}

	/** The launcher's side of {@link LibraryStart}; every report comes from the start thread. */
	private final class StartSteps implements LibraryStart.Steps {

		@Override
		public boolean needsUpgrade() throws MaintenanceException {
			return UpgradeBackup.needsUpgrade(home, DesktopLauncher.class.getClassLoader());
		}

		@Override
		public void backUp(LibraryBackup.Progress progress) throws MaintenanceException {
			UpgradeBackup.create(home, appVersion(), progress);
		}

		@Override
		public void progress(String stage, double fraction) {
			DesktopSplash.status(fraction < 0 ? "升级前正在备份图库…" : "升级前正在备份图库… " + Math.round(fraction * 100) + "%",
					SERVER_STAGE_CAP * Math.max(0, fraction) * 0.9);
		}

		@Override
		public void backedUp() {
			DesktopSplash.status("正在升级图库…", SERVER_STAGE_CAP);
		}

		@Override
		public void startServer() {
			DesktopLauncher.this.startServer();
		}

		@Override
		public void unreadable(String message) {
			// The window a library that does not open always gets, with its restore; nothing was changed.
			display.asyncExec(() -> startFailed(message));
		}

		@Override
		public void backupFailed(String message) {
			display.asyncExec(() -> upgradeBackupFailed(message));
		}
	}

	/** The library is still as it was. Quitting is the default; opening without a backup is the user's call. */
	private void upgradeBackupFailed(String message) {
		DesktopSplash.close();
		maintenanceWindow().result("升级图库前没能备份", "新版本需要升级图库，升级前会先自动备份一次，但这次备份没有完成：\n"
				+ message + "\n\n图库还没有任何改动。可以腾出空间或处理上面的问题后重新打开应用；"
				+ "也可以不备份直接打开：图库会立即升级，之后旧版本的应用就打不开它了。",
				List.of(new MaintenanceWindow.Action("不备份，直接打开", () -> {
							maintenanceWindow().close();
							startInBackground(false);
						}),
						new MaintenanceWindow.Action("退出", this::stop)), 1);
	}

	private void startServer() {
		try {
			DesktopServer.Running started = launch(false);
			if (stopping) {
				running = null;
				started.context().close();
				return;
			}
			display.asyncExec(() -> show(started));
		} catch (RuntimeException exception) {
			String message = LibraryMaintenance.message(exception);
			display.asyncExec(() -> startFailed(message));
		}
	}

	/**
	 * Starts the server on the library as it is now and connects the launcher to it; any thread but the UI's.
	 *
	 * @param held start with the gate closed, for a restored library that is not confirmed yet
	 */
	private DesktopServer.Running launch(boolean held) {
		DesktopServer.Running started = DesktopServer.start(home, choosePort(port), false, accessToken,
				Map.of(MaintenanceGate.START_CLOSED_PROPERTY, Boolean.toString(held)));
		port = started.port();
		started.context().getBean(DesktopActivation.class).onShow(() -> display.asyncExec(this::activate));
		started.context().getBean(MaintenanceController.class).connect(maintenance);
		running = started;
		rememberEntry(started);
		return started;
	}

	private void show(DesktopServer.Running started) {
		shown = true;
		if (window != null) {
			DesktopSplash.status("正在载入作品…", PAGE_STAGE_CAP);
			window.load(started.sessionUri());
			display.timerExec(REVEAL_ANYWAY_MILLIS, window::reveal);
		} else {
			controls.ready(started.baseUri().toString());
			openInBrowser();
		}
	}

	/** The library did not open. Quitting is the default; a backup can take its place. */
	private void startFailed(String message) {
		DesktopSplash.close();
		startFailure = message;
		maintenanceWindow().result("插画档案没能打开图库", message + "\n\n详见日志：" + home.resolve("logs").resolve("desktop.log")
				+ "\n\n如果是数据库损坏或丢失，可以从本应用生成的备份恢复整个图库；恢复前的图库会原样保留在数据文件夹里。",
				List.of(new MaintenanceWindow.Action("从备份恢复…", this::restoreAfterFailedStart),
						new MaintenanceWindow.Action("退出", this::stop)), 1);
	}

	/**
	 * A switch that could not be undone: the library is not whole where it belongs, so nothing may open it, a restore
	 * included. The journal and the folders stay as they are, and the next start tries the undo again before anything.
	 */
	private void stuck(String message) {
		DesktopSplash.close();
		maintenanceWindow().result("插画档案不能继续打开图库", message + "\n\n详见日志：" + home.resolve("logs").resolve("desktop.log"),
				List.of(new MaintenanceWindow.Action("退出", this::stop)), 0);
	}

	private void restoreAfterFailedStart() {
		Optional<Path> chosen = chooseBackup(maintenanceWindow().shell());
		if (chosen.isEmpty()) {
			return;
		}
		try {
			maintenance.check(chosen.get());
		} catch (MaintenanceException busy) {
			startFailed(busy.getMessage());
			return;
		}
		followCheck();
	}

	/** The page polls the check for itself; here the window does. */
	private void followCheck() {
		LibraryMaintenance.RestoreView view = maintenance.restoreView();
		MaintenanceWindow current = maintenanceWindow();
		switch (view.phase()) {
			case CHECKING -> {
				current.progress("正在检查备份", view.stage(), view.fraction(), maintenance::cancel);
				display.timerExec(CHECK_POLL_MILLIS, this::followCheck);
			}
			case READY -> current.result("从备份恢复整个图库？", LibraryMaintenance.describe(view.preview()), List.of(
					new MaintenanceWindow.Action("恢复整个图库", () -> confirmRestore(view.id())),
					new MaintenanceWindow.Action("取消", () -> {
						maintenance.cancelRestore(view.id());
						startFailed(startFailure);
					})), 1);
			case FAILED -> {
				maintenance.cancelRestore(null);
				startFailed(view.message());
			}
			case IDLE -> startFailed(startFailure);
		}
	}

	private void confirmRestore(String id) {
		try {
			maintenance.restore(id);
		} catch (MaintenanceException refused) {
			startFailed(refused.getMessage());
		}
	}

	private MaintenanceWindow maintenanceWindow() {
		if (maintenanceWindow == null || maintenanceWindow.isDisposed()) {
			Shell parent = shown && window != null ? window.shell() : controls != null ? controls.shell() : null;
			maintenanceWindow = new MaintenanceWindow(display, parent);
		}
		return maintenanceWindow;
	}

	/** Runs on the UI thread once a backup or restore ended, whether or not the server came back. */
	private void maintenanceFinished(LibraryMaintenance.Outcome outcome) {
		if (quitAfterMaintenance) {
			stop();
			return;
		}
		if (outcome.stuck()) {
			stuck(outcome.message());
			return;
		}
		if (!outcome.libraryOpen()) {
			startFailed(outcome.message());
			return;
		}
		boolean firstAppearance = !shown;
		reload();
		if (firstAppearance && maintenanceWindow != null) {
			// The app window is about to come to the front; a window it owns stays above it, a free one would not.
			maintenanceWindow.close();
		}
		boolean backup = outcome.kind() == LibraryMaintenance.Kind.BACKUP;
		String title = switch (outcome.kind()) {
			case BACKUP -> outcome.succeeded() ? "备份完成" : "没有完成备份";
			case RESTORE -> outcome.succeeded() ? "恢复完成" : "没有恢复";
			case IMPORT -> outcome.succeeded() ? "导入完成" : "没有导入";
		};
		List<MaintenanceWindow.Action> actions = new ArrayList<>();
		if (outcome.succeeded() && outcome.location() != null) {
			Path location = outcome.location();
			String open = switch (outcome.kind()) {
				case BACKUP -> "打开所在文件夹";
				case RESTORE -> "打开恢复前的图库";
				case IMPORT -> "打开导入前的图库";
			};
			actions.add(new MaintenanceWindow.Action(open, () -> reveal(location, backup)));
		}
		actions.add(new MaintenanceWindow.Action("完成", () -> maintenanceWindow().close()));
		maintenanceWindow().result(title, outcome.message(), actions, actions.size() - 1);
	}

	/** Back to the page the user started from, or, after a start that failed, the window's first appearance. */
	private void reload() {
		DesktopServer.Running current = running;
		if (current == null) {
			return;
		}
		if (!shown) {
			show(current);
		} else if (window != null) {
			window.reload(current.baseUri().resolve("/c/settings.html#data"));
		} else {
			controls.ready(current.baseUri().toString());
		}
	}

	private void reveal(Path location, boolean selectFile) {
		try {
			if (selectFile) {
				new ProcessBuilder("explorer.exe", "/select," + location).start();
			} else {
				Program.launch(location.toString());
			}
		} catch (IOException exception) {
			exception.printStackTrace();
		}
	}

	private Optional<Path> chooseBackup(Shell parent) {
		FileDialog dialog = new FileDialog(parent, SWT.OPEN);
		dialog.setText("选择要恢复的备份");
		dialog.setFilterNames(new String[] {"插画档案备份 (*.zip)"});
		dialog.setFilterExtensions(new String[] {"*.zip"});
		dialog.setFilterPath(documents().toString());
		return Optional.ofNullable(dialog.open()).map(Path::of);
	}

	private static Path documents() {
		Path documents = Path.of(System.getProperty("user.home"), "Documents");
		return Files.isDirectory(documents) ? documents : Path.of(System.getProperty("user.home"));
	}

	private Shell dialogParent() {
		if (maintenanceWindow != null && !maintenanceWindow.isDisposed()) {
			return maintenanceWindow.shell();
		}
		activate();
		return window != null ? window.shell() : controls.shell();
	}

	private <T> T onUiThread(Supplier<T> work) {
		AtomicReference<T> result = new AtomicReference<>();
		display.syncExec(() -> result.set(work.get()));
		return result.get();
	}

	/** What a backup or restore needs from the server: stop it once idle, start it again, and count rows. */
	private final class Server implements LibraryMaintenance.Server {

		@Override
		public boolean running() {
			return running != null;
		}

		@Override
		public MaintenanceGate gate() {
			return running.context().getBean(MaintenanceGate.class);
		}

		@Override
		public boolean stop() {
			DesktopServer.Running current = running;
			running = null;
			forgetEntry();
			return current == null || DesktopServer.stop(current);
		}

		@Override
		public void start(boolean held) {
			launch(held);
		}

		@Override
		public boolean loginInProgress() {
			return running.context().getBean(XAuthorizationService.class).status().state() == XAuthorizationService.State.WAITING;
		}

		@Override
		public long count(String table) {
			return running.context().getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
		}
	}

	/** Native file dialogs, opened on the UI thread for a request that waits for the answer. */
	private final class Dialogs implements LibraryMaintenance.Dialogs {

		@Override
		public Optional<Path> saveBackup(String suggestedName) {
			return onUiThread(() -> {
				FileDialog dialog = new FileDialog(dialogParent(), SWT.SAVE);
				dialog.setText("保存备份");
				dialog.setFilterNames(new String[] {"插画档案备份 (*.zip)"});
				dialog.setFilterExtensions(new String[] {"*.zip"});
				dialog.setFilterPath(documents().toString());
				dialog.setFileName(suggestedName);
				// The app refuses an existing file itself; Windows' own question would suggest replacing it.
				dialog.setOverwrite(false);
				return Optional.ofNullable(dialog.open()).map(Path::of);
			});
		}

		@Override
		public Optional<Path> openBackup() {
			return onUiThread(() -> chooseBackup(dialogParent()));
		}

		@Override
		public Optional<Path> openLegacyConfig() {
			return onUiThread(() -> {
				FileDialog dialog = new FileDialog(dialogParent(), SWT.OPEN);
				dialog.setText("选择旧版的配置文件 application-local.properties");
				dialog.setFilterNames(new String[] {"旧版配置 (application-local.properties)", "配置文件 (*.properties)"});
				dialog.setFilterExtensions(new String[] {"application-local.properties", "*.properties"});
				dialog.setFilterPath(documents().toString());
				return Optional.ofNullable(dialog.open()).map(Path::of);
			});
		}

		@Override
		public Optional<Path> chooseLegacyMedia(String configured) {
			return onUiThread(() -> {
				DirectoryDialog dialog = new DirectoryDialog(dialogParent());
				dialog.setText("选择旧版的媒体文件夹");
				dialog.setMessage("旧版配置里的媒体文件夹写的是相对位置“" + configured + "”，应用不猜它在哪里。"
						+ "请选择旧版实际存放原图的文件夹（里面有按月份分的子文件夹和 thumbnails）。");
				dialog.setFilterPath(documents().toString());
				return Optional.ofNullable(dialog.open()).map(Path::of);
			});
		}
	}

	private final class Screen implements LibraryMaintenance.Screen {

		@Override
		public void stage(LibraryMaintenance.Kind kind, String text, double fraction, boolean cancellable) {
			String title = switch (kind) {
				case BACKUP -> "正在备份图库";
				case RESTORE -> "正在恢复图库";
				case IMPORT -> "正在导入旧版图库";
			};
			display.asyncExec(() -> maintenanceWindow().progress(title, text, fraction, cancellable ? maintenance::cancel : null));
		}

		@Override
		public void finished(LibraryMaintenance.Outcome outcome) {
			display.asyncExec(() -> maintenanceFinished(outcome));
		}
	}

	private void activate() {
		if (window != null) {
			window.activate();
		} else {
			controls.activate();
		}
	}

	/** Only asked once the window has appeared and put its tray icon up, which is after the server started. */
	private CloseAction closeAction() {
		DesktopServer.Running current = running;
		// Down for a backup or restore: the page cannot answer, so the window asks by itself.
		return current == null ? CloseAction.ASK : current.context().getBean(DesktopSettings.class).closeAction();
	}

	private void stop() {
		if (maintenance.maintaining()) {
			// The server is down or the library half switched; quitting waits for the outcome.
			quitAfterMaintenance = true;
			return;
		}
		if (!stopping && window != null) {
			window.saveState();
		}
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

	private void forgetEntry() {
		try {
			Files.deleteIfExists(entryFile(home));
		} catch (IOException exception) {
			exception.printStackTrace();
		}
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

	private static int choosePort(int preferred) {
		try (ServerSocket probe = new ServerSocket(preferred, 1, InetAddress.getLoopbackAddress())) {
			return preferred;
		} catch (IOException busy) {
			return 0;
		}
	}

	private void shutDown() {
		int code = 0;
		try {
			// Before anything is released: a backup stops at its next step, but an upgrade under way runs to its end,
			// and until then the library is in use and the instance lock must keep a second launch out.
			starter.awaitEnd();
			maintenance.close();
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
