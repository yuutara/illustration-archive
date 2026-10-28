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
 * <p>The server only listens on 127.0.0.1. This filter additionally blocks what loopback binding cannot:
 * DNS-rebinding pages (unexpected Host header), other websites in the same browser (no SameSite=Strict
 * session cookie, so no cross-site reads of media or API data), and writes from other local origins such
 * as a dev server on another port, which browsers treat as same-site.
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

	private final byte[] accessToken;

	public LocalAccessFilter(@Value("${" + DesktopServer.ACCESS_TOKEN_PROPERTY + "}") String accessToken) {
		if (accessToken.length() < 32) {
			throw new IllegalStateException("Desktop access token is missing or too short.");
		}
		this.accessToken = accessToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String host = request.getHeader(HttpHeaders.HOST);
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
