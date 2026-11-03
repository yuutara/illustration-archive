package com.yuutara.illustrationarchive.desktop;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import java.nio.file.Path;

/**
 * The small window for machines without WebView2: the app then runs in a browser, which has no way to quit it. Normal
 * launches never show this.
 */
final class DesktopControls {

	private final Shell shell;
	private final Label status;
	private final Label detail;
	private final Button openGallery;

	DesktopControls(Display display, Path home, Runnable openApp, Runnable quit) {
		shell = new Shell(display, SWT.DIALOG_TRIM | SWT.MIN);
		shell.setText("插画档案");
		GridLayout layout = new GridLayout(3, false);
		layout.marginWidth = 16;
		layout.marginHeight = 16;
		layout.verticalSpacing = 8;
		shell.setLayout(layout);
		status = line("未检测到 Microsoft Edge WebView2，正在启动…");
		detail = line(" ");
		line("数据目录：" + home);
		Button openData = button("打开数据文件夹", () -> Program.launch(home.toString()));
		openGallery = button("打开图库", openApp);
		openGallery.setEnabled(false);
		button("退出", quit);
		shell.addListener(SWT.Close, event -> {
			event.doit = false;
			quit.run();
		});
		openData.setFocus();
		shell.pack();
		shell.open();
	}

	private Label line(String text) {
		Label label = new Label(shell, SWT.NONE);
		label.setText(text);
		label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
		return label;
	}

	private Button button(String text, Runnable action) {
		Button button = new Button(shell, SWT.PUSH);
		button.setText(text);
		button.addListener(SWT.Selection, event -> action.run());
		return button;
	}

	void ready(String address) {
		status.setText("运行中：仅本机可访问。用完后请点这里的“退出”。");
		detail.setText(address);
		openGallery.setEnabled(true);
		shell.pack();
	}

	Shell shell() {
		return shell;
	}

	void activate() {
		shell.setMinimized(false);
		shell.forceActive();
	}
}
