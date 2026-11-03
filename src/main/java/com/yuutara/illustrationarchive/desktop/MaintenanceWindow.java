package com.yuutara.illustrationarchive.desktop;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The native window for backups, restores and a library that does not open. While the server is down the app's page
 * cannot show anything, so this says what is happening, offers Cancel only while cancelling still leaves the library
 * as it was, and then shows the outcome. Over the app window it is modal, so the gallery cannot be used meanwhile;
 * closing it does what its last button does, and while a switch is under way it does nothing.
 */
final class MaintenanceWindow {

	private static final int TEXT_WIDTH = 440;
	private static final int[] ICON_SIZES = {16, 32, 48};

	record Action(String label, Runnable run) {
	}

	private final Shell shell;
	private final Label heading;
	private final Label message;
	private final ProgressBar measured;
	private final ProgressBar unmeasured;
	private final Composite buttons;
	private final Font bold;
	private Runnable onClose = () -> { };

	MaintenanceWindow(Display display, Shell parent) {
		shell = parent == null ? new Shell(display, SWT.DIALOG_TRIM)
				: new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
		shell.setText("插画档案");
		Image[] icons = IntStream.of(ICON_SIZES).mapToObj(size -> new Image(display, DesktopWindow.iconData(size)))
				.toArray(Image[]::new);
		shell.setImages(icons);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 24;
		layout.marginHeight = 20;
		layout.verticalSpacing = 12;
		shell.setLayout(layout);
		FontData[] base = display.getSystemFont().getFontData();
		bold = new Font(display, base[0].getName(), base[0].getHeight() + 2, SWT.BOLD);
		heading = new Label(shell, SWT.WRAP);
		heading.setFont(bold);
		heading.setLayoutData(wide());
		message = new Label(shell, SWT.WRAP);
		message.setLayoutData(wide());
		measured = new ProgressBar(shell, SWT.SMOOTH);
		measured.setMaximum(1000);
		measured.setLayoutData(wide());
		unmeasured = new ProgressBar(shell, SWT.INDETERMINATE);
		unmeasured.setLayoutData(wide());
		buttons = new Composite(shell, SWT.NONE);
		RowLayout row = new RowLayout(SWT.HORIZONTAL);
		row.marginWidth = 0;
		row.marginHeight = 0;
		row.spacing = 8;
		buttons.setLayout(row);
		buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		shell.addListener(SWT.Close, event -> {
			event.doit = false;
			onClose.run();
		});
		shell.addListener(SWT.Dispose, event -> {
			bold.dispose();
			for (Image icon : icons) {
				icon.dispose();
			}
		});
	}

	private static GridData wide() {
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
		data.widthHint = TEXT_WIDTH;
		return data;
	}

	Shell shell() {
		return shell;
	}

	boolean isDisposed() {
		return shell.isDisposed();
	}

	/**
	 * @param fraction between 0 and 1, or negative while unknown
	 * @param cancel what Cancel does, or null when the stage cannot be cancelled
	 */
	void progress(String title, String text, double fraction, Runnable cancel) {
		heading.setText(title);
		message.setText(text);
		boolean known = fraction >= 0;
		show(measured, known);
		show(unmeasured, !known);
		if (known) {
			measured.setSelection((int) Math.round(Math.min(1, fraction) * 1000));
		}
		Runnable stop = cancel == null ? () -> { } : cancel;
		if (buttons.getChildren().length != (cancel == null ? 0 : 1)) {
			clearButtons();
			if (cancel != null) {
				button("取消", stop);
			}
		}
		onClose = stop;
		layOut();
	}

	/** @param closeAction the action the close button runs, which also has the focus */
	void result(String title, String text, List<Action> actions, int closeAction) {
		heading.setText(title);
		message.setText(text);
		show(measured, false);
		show(unmeasured, false);
		clearButtons();
		Button focused = null;
		for (int i = 0; i < actions.size(); i++) {
			Button button = button(actions.get(i).label(), actions.get(i).run());
			if (i == closeAction) {
				focused = button;
			}
		}
		onClose = actions.get(closeAction).run();
		layOut();
		shell.forceActive();
		if (focused != null) {
			shell.setDefaultButton(focused);
			focused.setFocus();
		}
	}

	void close() {
		if (!shell.isDisposed()) {
			shell.dispose();
		}
	}

	private Button button(String text, Runnable action) {
		Button button = new Button(buttons, SWT.PUSH);
		button.setText(text);
		button.addListener(SWT.Selection, event -> action.run());
		return button;
	}

	private void clearButtons() {
		for (Control child : buttons.getChildren()) {
			child.dispose();
		}
	}

	private static void show(Control control, boolean visible) {
		control.setVisible(visible);
		((GridData) control.getLayoutData()).exclude = !visible;
	}

	/** Grows or shrinks to the text, which moves nothing while only the progress changes. */
	private void layOut() {
		Point size = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT);
		if (!size.equals(shell.getSize())) {
			shell.setSize(size);
		}
		shell.layout(true, true);
		if (!shell.isVisible()) {
			Rectangle area = shell.getParent() instanceof Shell parent && parent.isVisible() ? parent.getBounds()
					: shell.getDisplay().getPrimaryMonitor().getClientArea();
			shell.setLocation(area.x + (area.width - size.x) / 2, area.y + (area.height - size.y) / 3);
			shell.open();
			shell.forceActive();
		}
	}
}
