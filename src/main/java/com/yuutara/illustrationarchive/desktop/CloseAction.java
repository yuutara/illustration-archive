package com.yuutara.illustrationarchive.desktop;

import java.util.Locale;

/** What the window's close button does. Asking is the default, so a first close never hides the app without saying so. */
public enum CloseAction {

	ASK, TRAY, EXIT;

	/** Reads the saved preference; a missing or unknown value only costs the question. */
	static CloseAction stored(String value) {
		for (CloseAction action : values()) {
			if (action.stored().equals(value)) {
				return action;
			}
		}
		return ASK;
	}

	String stored() {
		return name().toLowerCase(Locale.ROOT);
	}
}
