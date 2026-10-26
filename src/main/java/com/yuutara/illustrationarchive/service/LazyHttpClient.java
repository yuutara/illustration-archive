package com.yuutara.illustrationarchive.service;

import java.net.http.HttpClient;
import java.util.function.Supplier;

/**
 * Builds a default JDK HttpClient on first use. X and Gemini are optional and usually disabled, and creating
 * a client opens a selector with a local loopback channel; doing that at startup for an unused integration
 * can prevent the whole application from starting in a restricted environment.
 */
final class LazyHttpClient implements Supplier<HttpClient> {

	private volatile HttpClient client;

	@Override
	public HttpClient get() {
		HttpClient current = client;
		if (current == null) {
			synchronized (this) {
				current = client;
				if (current == null) {
					current = HttpClient.newHttpClient();
					client = current;
				}
			}
		}
		return current;
	}
}
