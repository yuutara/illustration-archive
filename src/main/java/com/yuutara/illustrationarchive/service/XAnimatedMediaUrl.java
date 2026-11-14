package com.yuutara.illustrationarchive.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Shared by normalization and download so only downloadable MP4 variants become pending. */
final class XAnimatedMediaUrl {
	static final String DEFAULT_BASE_URL = "https://video.twimg.com";

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

	static URI resolve(String url, String baseUrl) {
		URI parsed = parse(url);
		if (baseUrl == null || baseUrl.isBlank()) {
			return parsed;
		}
		URI base = URI.create(baseUrl.trim());
		String scheme = base.getScheme();
		if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) || base.getHost() == null) {
			throw new IllegalArgumentException("Invalid X video CDN base URL.");
		}
		String basePath = base.getPath() == null || base.getPath().equals("/") ? "" : base.getPath().replaceAll("/+$", "");
		try {
			return new URI(base.getScheme(), null, base.getHost(), base.getPort(),
					basePath + parsed.getPath(), parsed.getQuery(), null);
		} catch (URISyntaxException failure) {
			throw new IllegalArgumentException("X animated media URL could not be constructed.", failure);
		}
	}

	static boolean valid(String url) {
		try { parse(url); return true; }
		catch (IllegalArgumentException failure) { return false; }
	}
}
