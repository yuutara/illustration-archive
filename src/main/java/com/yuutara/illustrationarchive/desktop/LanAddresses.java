package com.yuutara.illustrationarchive.desktop;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** IPv4 addresses a phone on the same home network can dial. The Settings page lets the user pick when there are several. */
final class LanAddresses {

	private LanAddresses() {
	}

	private record Candidate(int interfaceRank, int addressRank, String address) {
	}

	static List<String> siteLocal() {
		Set<Candidate> found = new LinkedHashSet<>();
		try {
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!usable(network)) {
					continue;
				}
				String name = (network.getName() + " " + network.getDisplayName()).toLowerCase(Locale.ROOT);
				int interfaceRank = interfaceRank(name);
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()
							&& !address.isLinkLocalAddress()) {
						found.add(new Candidate(interfaceRank, addressRank(address.getHostAddress()), address.getHostAddress()));
					}
				}
			}
		} catch (SocketException unreadable) {
			return List.of();
		}
		return found.stream()
				.sorted(Comparator.comparingInt(Candidate::interfaceRank)
						.thenComparingInt(Candidate::addressRank)
						.thenComparing(Candidate::address))
				.map(Candidate::address)
				.toList();
	}

	private static boolean usable(NetworkInterface network) throws SocketException {
		return network.isUp() && !network.isLoopback() && !network.isPointToPoint() && !network.isVirtual();
	}

	/**
	 * Wi-Fi / wired first, then anything undocumented, then the usual virtual adapters. A VMware host-only address is
	 * still offered in the picker, but it is not the default a phone should scan.
	 */
	private static int interfaceRank(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.contains("vmware") || lower.contains("virtualbox") || lower.contains("hyper-v")
				|| lower.contains("vethernet") || lower.contains("tailscale") || lower.contains("zerotier")
				|| lower.contains("wireguard") || lower.contains("tunnel") || lower.contains("vpn")
				|| lower.contains("radmin") || lower.contains("mihomo") || lower.contains("atrust")
				|| lower.contains("wintun") || lower.contains("bluetooth")) {
			return 3;
		}
		if (lower.contains("wlan") || lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("wireless")) {
			return 0;
		}
		if (lower.contains("ethernet") || lower.contains("以太网")) {
			return 1;
		}
		return 2;
	}

	/** Home routers are usually 192.168.*, then 10.*, then 172.* / Docker ranges. */
	private static int addressRank(String address) {
		if (address.startsWith("192.168.")) {
			return 0;
		}
		if (address.startsWith("10.")) {
			return 1;
		}
		if (address.startsWith("172.")) {
			return 2;
		}
		return 3;
	}
}
