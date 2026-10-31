package com.yuutara.illustrationarchive.desktop;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tray;
import org.eclipse.swt.widgets.TrayItem;

/**
 * The notification-area icon: a click brings the window back, the right-click menu also quits. Windows drops icons
 * when Explorer restarts and SWT puts them back; disposing the display, as the launcher does on its way out, removes
 * this one, so no stale icon is left behind.
 */
final class DesktopTray {

	private DesktopTray() {
	}

	/** @return false where Windows offers no tray, in which case closing the window can only quit */
	static boolean install(Shell shell, String title, Runnable show, Runnable quit) {
		Display display = shell.getDisplay();
		Tray tray = display.getSystemTray();
		if (tray == null) {
			return false;
		}
		TrayItem item = new TrayItem(tray, SWT.NONE);
		item.setToolTipText(title);
		// The provider ignores SWT's zoom: this is already the exact size Windows draws, and scaling would blur it.
		ImageData icon = DesktopWindow.iconData(iconSize(OS.GetSystemMetrics(OS.SM_CXSMICON)));
		item.setImage(new Image(display, (ImageDataProvider) zoom -> icon));

		Menu menu = new Menu(shell, SWT.POP_UP);
		MenuItem open = menuItem(menu, "打开" + title, show);
		new MenuItem(menu, SWT.SEPARATOR);
		menuItem(menu, "退出", quit);
		menu.setDefaultItem(open);

		item.addListener(SWT.Selection, event -> show.run());
		item.addListener(SWT.MenuDetect, event -> menu.setVisible(true));
		return true;
	}

	private static MenuItem menuItem(Menu menu, String text, Runnable action) {
		MenuItem item = new MenuItem(menu, SWT.PUSH);
		item.setText(text);
		item.addListener(SWT.Selection, event -> action.run());
		return item;
	}

	/** The smallest icon that is not scaled up, so 125 % and 150 % displays get their hand-sized versions. */
	static int iconSize(int wanted) {
		for (int size : DesktopWindow.ICON_SIZES) {
			if (size >= wanted) {
				return size;
			}
		}
		return DesktopWindow.ICON_SIZES[DesktopWindow.ICON_SIZES.length - 1];
	}
}
