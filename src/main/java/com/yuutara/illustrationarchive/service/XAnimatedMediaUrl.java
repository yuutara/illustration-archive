package com.yuutara.illustrationarchive.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Shared by normalization and download so only downloadable MP4 variants become pending. */
final class XAnimatedMediaUrl {
	private XAnimatedMediaUrl() { }

	static URI parse(String url) {
		try {
			URI uri = new URI(url);
			if ("https".equalsIgnoreCase(uri.getScheme())
					&& "video.twimg.com".equalsIgnoreCase(uri.getHost())
					&& uri.getPort() == -1 && uri.getUserInfo() == null && uri.getFragment() == null
					&& uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
				return uri;
			}
		} catch (URISyntaxException | NullPointerException ignored) { }
		throw new IllegalArgumentException("X animated media requires an HTTPS video.twimg.com MP4 URL.");
	}

	static boolean valid(String url) {
		try { parse(url); return true; }
		catch (IllegalArgumentException failure) { return false; }
	}
}
