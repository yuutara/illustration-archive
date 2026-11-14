package com.yuutara.illustrationarchive.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/** Shared by X photo archiving and preview proxy so only pbs.twimg.com media images are requested. */
final class XPhotoMediaUrl {
	static final String DEFAULT_BASE_URL = "https://pbs.twimg.com";
	private static final String CDN_HOST = "pbs.twimg.com";
	private static final Set<String> PREVIEW_SIZES = Set.of("small", "medium", "large", "4096x4096");

	private XPhotoMediaUrl() { }

	static PhotoVariant parse(String sourceUrl, String variant) {
		return resolve(sourceUrl, variant, DEFAULT_BASE_URL);
	}

	static PhotoVariant resolve(String sourceUrl, String variant, String baseUrl) {
		URI source;
		try {
			source = new URI(sourceUrl);
		} catch (URISyntaxException | NullPointerException exception) {
			throw new XPhotoDownloadException("X photo source URL is invalid.", exception);
		}
		if (!"https".equalsIgnoreCase(source.getScheme()) || !CDN_HOST.equalsIgnoreCase(source.getHost())
				|| source.getPort() != -1 || source.getUserInfo() != null || source.getFragment() != null) {
			throw new XPhotoDownloadException("X photo source URL must be an HTTPS X media CDN URL.");
		}
		String path = source.getPath();
		int lastSlash = path == null ? -1 : path.lastIndexOf('/');
		if (lastSlash != "/media/".length() - 1 || !path.startsWith("/media/")) {
			throw new XPhotoDownloadException("X photo source URL has an unsupported media path.");
		}
		String name = path.substring(lastSlash + 1);
		if (name.contains("..")) {
			throw new XPhotoDownloadException("X photo source URL has an unsupported media path.");
		}
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			throw new XPhotoDownloadException("X photo source URL has no supported image extension.");
		}
		String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
		String format;
		String contentType;
		switch (extension) {
			case "jpg", "jpeg" -> {
				format = "jpg";
				contentType = "image/jpeg";
			}
			case "png" -> {
				format = "png";
				contentType = "image/png";
			}
			default -> throw new XPhotoDownloadException("X photo source URL has an unsupported image extension.");
		}
		String baseName = name.substring(0, dot);
		URI base = normalizeBase(baseUrl);
		String basePath = base.getPath() == null || base.getPath().equals("/") ? "" : base.getPath().replaceAll("/+$", "");
		try {
			URI uri = new URI(base.getScheme(), null, base.getHost(), base.getPort(),
					basePath + "/media/" + baseName,
					"format=" + format + "&name=" + variant, null);
			return new PhotoVariant(uri, name, contentType);
		} catch (URISyntaxException exception) {
			throw new XPhotoDownloadException("X photo CDN URL could not be constructed.", exception);
		}
	}

	static String normalizePreviewSize(String size) {
		if (size == null || size.isBlank()) {
			return "large";
		}
		String normalized = size.trim().toLowerCase(Locale.ROOT);
		if (!PREVIEW_SIZES.contains(normalized)) {
			throw new IllegalArgumentException("Unsupported X photo preview size.");
		}
		return normalized;
	}

	private static URI normalizeBase(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return URI.create(DEFAULT_BASE_URL);
		}
		URI base = URI.create(baseUrl.trim());
		String scheme = base.getScheme();
		if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) || base.getHost() == null) {
			throw new IllegalArgumentException("Invalid X photo CDN base URL.");
		}
		return base;
	}

	record PhotoVariant(URI uri, String filename, String contentType) {
	}
}
