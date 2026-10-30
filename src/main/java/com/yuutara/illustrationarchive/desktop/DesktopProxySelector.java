package com.yuutara.illustrationarchive.desktop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Lets X and Gemini requests follow the proxy chosen in Settings. JDK HttpClient captures the default selector
 * once when it is built, so the choice lives here and can change without rebuilding any client. With no manual
 * proxy the wrapped selector applies, which follows the Windows proxy settings ({@code java.net.useSystemProxies}).
 */
final class DesktopProxySelector extends ProxySelector {

	private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");

	private final ProxySelector system;
	private volatile InetSocketAddress manual;

	private DesktopProxySelector(ProxySelector system) {
		this.system = system;
	}

	static synchronized DesktopProxySelector installed() {
		if (ProxySelector.getDefault() instanceof DesktopProxySelector current) {
			return current;
		}
		DesktopProxySelector selector = new DesktopProxySelector(ProxySelector.getDefault());
		ProxySelector.setDefault(selector);
		return selector;
	}

	void useManual(InetSocketAddress address) {
		manual = address;
	}

	@Override
	public List<Proxy> select(URI uri) {
		InetSocketAddress address = manual;
		if (address == null || LOOPBACK.contains(String.valueOf(uri.getHost()))) {
			return system == null ? List.of(Proxy.NO_PROXY) : system.select(uri);
		}
		return List.of(new Proxy(Proxy.Type.HTTP, address));
	}

	@Override
	public void connectFailed(URI uri, SocketAddress address, IOException failure) {
		if (system != null) {
			system.connectFailed(uri, address, failure);
		}
	}
}
