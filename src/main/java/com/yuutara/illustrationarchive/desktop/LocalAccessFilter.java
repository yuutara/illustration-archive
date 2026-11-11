package com.yuutara.illustrationarchive.desktop;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * Local access protection for the desktop PoC, which has no user accounts.
 *
 * <p>The main server only listens on 127.0.0.1. This filter additionally blocks what loopback binding cannot:
 * DNS-rebinding pages (unexpected Host header), other websites in the same browser (no SameSite=Strict session cookie,
 * so no cross-site reads of media or API data), and writes from other local origins such as a dev server on another
 * port, which browsers treat as same-site.
 *
 * <p>When the user turns on phone access, a second Tomcat connector listens on the LAN. Requests there take the phone
 * branch: a paired device cookie is required, only read-only gallery and asset paths are allowed, and a QR entry page
 * is the one public route. Turning the feature off removes the connector, so a request cannot even reach this filter.
 */
@Component
@Profile(DesktopServer.PROFILE)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalAccessFilter extends OncePerRequestFilter {

	public static final String SESSION_PATH = "/desktop/session";
	/** Where the control window's "打开图库" lands once the session cookie is set; the old gallery stays at "/". */
	public static final String LANDING_PATH = "/c/";
	static final String COOKIE_NAME = "ia_desktop_session";
	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
	/** The phone pages and the exact shared style / script files they use. No directory prefix, so "../c/" cannot pass. */
	private static final Set<String> PHONE_FILES = Set.of(
			"/m", "/m/", "/m/index.html", "/m/item.html", "/m/pair.html",
			"/m/app.css", "/m/app.js", "/m/item.js",
			"/c/gallery.css", "/c/theme-quiet.css", "/c/motion.css", "/c/theme-mode.js");

	private final byte[] accessToken;
	private final MobileAccess mobile;

	public LocalAccessFilter(@Value("${" + DesktopServer.ACCESS_TOKEN_PROPERTY + "}") String accessToken, MobileAccess mobile) {
		if (accessToken.length() < 32) {
			throw new IllegalStateException("Desktop access token is missing or too short.");
		}
		this.accessToken = accessToken.getBytes(StandardCharsets.UTF_8);
		this.mobile = mobile;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String host = request.getHeader(HttpHeaders.HOST);
		if (isPhoneLane(request)) {
			phoneRequest(request, response, chain, host);
			return;
		}
		if (!isLocalHost(host, request.getLocalPort())) {
			reject(response);
			return;
		}
		if (SESSION_PATH.equals(request.getRequestURI())) {
			if (!matchesToken(request.getParameter("token"))) {
				reject(response);
				return;
			}
			ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, new String(accessToken, StandardCharsets.UTF_8))
					.httpOnly(true).sameSite("Strict").path("/").build();
			response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.sendRedirect(LANDING_PATH);
			return;
		}
		if (!matchesToken(sessionCookie(request))) {
			reject(response);
			return;
		}
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (!SAFE_METHODS.contains(request.getMethod()) && origin != null
				&& !origin.equalsIgnoreCase("http://" + host)) {
			reject(response);
			return;
		}
		chain.doFilter(request, response);
	}

	/** The phone connector is identified by its actual port, not by the Host header a caller can choose. */
	private boolean isPhoneLane(HttpServletRequest request) {
		int port = mobile.port();
		return port > 0 && request.getLocalPort() == port;
	}

	private void phoneRequest(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String host)
			throws ServletException, IOException {
		if (!isPhoneHost(host, request.getLocalPort())) {
			rejectMobile(response, request, false);
			return;
		}
		String path = request.getRequestURI();
		if (SAFE_METHODS.contains(request.getMethod()) && (path.equals("/m/pair") || path.equals("/m/pair.html"))) {
			chain.doFilter(request, response);
			return;
		}
		if (!mobile.acceptsDevice(phoneCookie(request))) {
			rejectMobile(response, request, true);
			return;
		}
		if (!SAFE_METHODS.contains(request.getMethod()) || !phonePathAllowed(path)) {
			rejectJson(response, HttpServletResponse.SC_FORBIDDEN, "这台手机只能查看图库。");
			return;
		}
		chain.doFilter(request, response);
	}

	/** Accepts localhost and IPv4 literals with the phone port; a rebinding page's own hostname never matches. */
	private static boolean isPhoneHost(String host, int port) {
		if (host == null) {
			return false;
		}
		String normalized = host.toLowerCase(Locale.ROOT);
		String suffix = ":" + port;
		if (normalized.equals("localhost" + suffix) || normalized.equals("127.0.0.1" + suffix)) {
			return true;
		}
		if (!normalized.endsWith(suffix)) {
			return false;
		}
		return normalized.substring(0, normalized.length() - suffix.length()).matches("\\d{1,3}(\\.\\d{1,3}){3}");
	}

	private static boolean phonePathAllowed(String path) {
		if (PHONE_FILES.contains(path)) {
			return true;
		}
		if (path.equals("/api/illustrations")) {
			return true;
		}
		if (path.matches("/api/illustrations/\\d+")) {
			return true;
		}
		return path.matches("/api/assets/\\d+/(content|thumbnail)");
	}

	private static String phoneCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (MobileAccess.DEVICE_COOKIE.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	/** A page navigated from history without a device cookie goes to the explanation, not a bare 403. */
	private static void rejectMobile(HttpServletResponse response, HttpServletRequest request, boolean unpaired)
			throws IOException {
		String path = request.getRequestURI();
		if (unpaired && (path.equals("/m") || path.equals("/m/") || path.endsWith(".html"))) {
			response.setStatus(HttpServletResponse.SC_FOUND);
			response.setHeader(HttpHeaders.LOCATION, "/m/pair.html?e=2");
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			return;
		}
		if (path.startsWith("/api/")) {
			rejectJson(response, HttpServletResponse.SC_FORBIDDEN,
					unpaired ? "这台手机还没有配对，或配对已被撤销。" : "这页内容不能在手机上查看。");
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.getWriter().write(unpaired ? "这台手机还没有配对。请在电脑上重新扫码。" : "这页内容不能在手机上查看。");
	}

	private static void rejectJson(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.getWriter().write("{\"code\":\"MOBILE_FORBIDDEN\",\"message\":\"" + message + "\"}");
	}

	private static boolean isLocalHost(String host, int port) {
		if (host == null) {
			return false;
		}
		String normalized = host.toLowerCase(Locale.ROOT);
		return normalized.equals("127.0.0.1:" + port) || normalized.equals("localhost:" + port);
	}

	private boolean matchesToken(String candidate) {
		return candidate != null
				&& MessageDigest.isEqual(accessToken, candidate.getBytes(StandardCharsets.UTF_8));
	}

	private static String sessionCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private static void reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.getWriter().write("本机访问保护：请从 Illustration Archive 控制窗口点击“打开图库”重新进入。");
	}
}
