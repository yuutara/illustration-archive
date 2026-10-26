package com.yuutara.illustrationarchive.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Small control window for the desktop PoC: start the local server, open the gallery in the system browser,
 * open the data folder, and exit. Exiting closes the Spring context, which drains requests, closes the
 * connection pool (and with it the H2 file), and releases the port before the process ends.
 */
public final class DesktopLauncher {

	/** Stable port keeps browser-side preferences (theme, layout) between launches; falls back if busy. */
	static final int PREFERRED_PORT = 47391;

	private final Path home;
	private final FileLock instanceLock;
	private final JFrame frame = new JFrame("Illustration Archive · Windows PoC");
	private final JLabel status = new JLabel("正在启动本地图库…");
	private final JLabel detail = new JLabel(" ");
	private final JButton openGallery = new JButton("打开图库");
	private final JButton openData = new JButton("打开数据文件夹");
	private final JButton exit = new JButton("退出");
	private volatile DesktopServer.Running running;
	private volatile boolean stopping;

	private DesktopLauncher(Path home, FileLock instanceLock) {
		this.home = home;
		this.instanceLock = instanceLock;
	}

	public static void main(String[] args) {
		Path home = DesktopServer.defaultHome();
		FileLock lock;
		try {
			Files.createDirectories(home);
			lock = FileChannel.open(home.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
					.tryLock();
		} catch (IOException exception) {
			showFatal("无法使用数据目录：" + home + "\n" + exception.getMessage());
			return;
		}
		if (lock == null) {
			showFatal("Illustration Archive 已在运行。\n请使用已打开的控制窗口（可能在任务栏中）。");
			return;
		}
		DesktopLauncher launcher = new DesktopLauncher(home, lock);
		SwingUtilities.invokeLater(launcher::showWindow);
		// Created from the main thread so Spring sees the Boot launcher's class loader.
		ClassLoader loader = DesktopLauncher.class.getClassLoader();
		Thread starter = new Thread(() -> {
			Thread.currentThread().setContextClassLoader(loader);
			launcher.startServer();
		}, "desktop-start");
		starter.start();
	}

	private void showWindow() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			SwingUtilities.updateComponentTreeUI(frame);
		} catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
			// The default look and feel is still fully usable.
		}
		openGallery.setEnabled(false);
		openGallery.addActionListener(event -> openGallery());
		openData.addActionListener(event -> openPath(home));
		exit.addActionListener(event -> exit());

		JPanel text = new JPanel(new GridLayout(0, 1, 0, 6));
		text.add(status);
		text.add(detail);
		text.add(new JLabel("数据目录：" + home));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(openData);
		buttons.add(openGallery);
		buttons.add(exit);
		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
		content.add(text, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		frame.setContentPane(content);
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				exit();
			}
		});
		frame.pack();
		frame.setMinimumSize(frame.getSize());
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private void startServer() {
		try {
			DesktopServer.Running started = DesktopServer.start(home, choosePort(), false);
			if (stopping) {
				started.context().close();
				return;
			}
			running = started;
			SwingUtilities.invokeLater(() -> {
				status.setText("运行中：仅本机可访问");
				detail.setText(started.baseUri().toString());
				openGallery.setEnabled(true);
			});
			openGallery();
		} catch (RuntimeException exception) {
			Throwable cause = exception;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			String message = cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
			SwingUtilities.invokeLater(() -> {
				status.setText("启动失败");
				detail.setText("详见日志：" + home.resolve("logs").resolve("desktop.log"));
				JOptionPane.showMessageDialog(frame, "启动失败：\n" + message, "Illustration Archive",
						JOptionPane.ERROR_MESSAGE);
			});
		}
	}

	private static int choosePort() {
		try (ServerSocket probe = new ServerSocket(PREFERRED_PORT, 1, InetAddress.getLoopbackAddress())) {
			return PREFERRED_PORT;
		} catch (IOException busy) {
			return 0;
		}
	}

	private void openGallery() {
		DesktopServer.Running current = running;
		if (current != null) {
			browse(current.sessionUri());
		}
	}

	private void exit() {
		if (stopping) {
			return;
		}
		stopping = true;
		openGallery.setEnabled(false);
		exit.setEnabled(false);
		status.setText("正在停止…");
		Thread stopper = new Thread(() -> {
			int code = 0;
			try {
				DesktopServer.Running current = running;
				if (current != null) {
					current.context().close();
				}
			} catch (RuntimeException exception) {
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
		}, "desktop-stop");
		stopper.start();
	}

	private void browse(URI uri) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
			} else {
				new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start();
			}
		} catch (IOException exception) {
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
					"无法打开浏览器，请手动访问：\n" + uri,
					"Illustration Archive", JOptionPane.WARNING_MESSAGE));
		}
	}

	private void openPath(Path path) {
		try {
			Desktop.getDesktop().open(path.toFile());
		} catch (IOException | UnsupportedOperationException exception) {
			JOptionPane.showMessageDialog(frame, "无法打开文件夹：" + path, "Illustration Archive",
					JOptionPane.WARNING_MESSAGE);
		}
	}

	private static void showFatal(String message) {
		JOptionPane.showMessageDialog(null, message, "Illustration Archive", JOptionPane.WARNING_MESSAGE);
	}
}
