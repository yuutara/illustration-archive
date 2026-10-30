package com.yuutara.illustrationarchive.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** A second launch means "show me the app": it asks the server, and this hands that to the running launcher. */
@Component
@Profile(DesktopServer.PROFILE)
public class DesktopActivation {

	private volatile Runnable onShow = () -> { };

	void onShow(Runnable action) {
		onShow = action;
	}

	void show() {
		onShow.run();
	}
}
