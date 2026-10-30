package com.yuutara.illustrationarchive.desktop;

import java.util.Locale;

/**
 * What the app window does with a navigation. It only ever shows the app's own pages: the bridge to Java is
 * available to whatever page is showing, and a window without an address bar gives no way back from anywhere else.
 */
enum LinkPolicy {
	/** One of the app's pages. */
	STAY,
	/** A web page, which belongs in the user's default browser. */
	EXTERNAL,
	/** Everything else, such as an image dropped beside the drop area, which would otherwise replace the app. */
	BLOCK;

	static LinkPolicy of(String location, String appOrigin) {
		String address = location == null ? "" : location.toLowerCase(Locale.ROOT);
		if (address.equals("about:blank") || address.equals(appOrigin) || address.startsWith(appOrigin + "/")) {
			return STAY;
		}
		return address.startsWith("https://") || address.startsWith("http://") ? EXTERNAL : BLOCK;
	}
}
