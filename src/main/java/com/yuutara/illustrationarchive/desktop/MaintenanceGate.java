package com.yuutara.illustrationarchive.desktop;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Lets a backup or restore know for certain that nothing is changing the library. Every request the app serves does
 * its work inside the request, imports, deletes and X syncs included, with their file clean-up, so once the gate is
 * closed and the requests already inside have returned, nothing writes to the database or the media folder. Closed,
 * it answers every request, from any page or tab, with 503.
 *
 * <p>Spring's graceful shutdown cannot give that answer: when its timeout runs out it stops anyway. Here running out
 * of time just reopens the gate, and the library was never touched.
 */
@Component
@Profile(DesktopServer.PROFILE)
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MaintenanceGate extends OncePerRequestFilter {

	/** A server started on a restored library begins closed, so the gate's state carries over the restart. */
	public static final String START_CLOSED_PROPERTY = "illustration-archive.desktop.start-closed";
	private static final long CHECK_MILLIS = 200;

	private int active;
	private boolean closed;

	public MaintenanceGate(@Value("${" + START_CLOSED_PROPERTY + ":false}") boolean closed) {
		this.closed = closed;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!enter()) {
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			response.setContentType("application/json;charset=UTF-8");
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.getWriter().write("{\"message\":\"正在备份或恢复图库，完成后会自动回来。\"}");
			return;
		}
		try {
			chain.doFilter(request, response);
		} finally {
			leave();
		}
	}

	private synchronized boolean enter() {
		if (closed) {
			return false;
		}
		active++;
		return true;
	}

	private synchronized void leave() {
		active--;
		notifyAll();
	}

	/**
	 * Refuses new requests from now on and waits for those inside to return.
	 *
	 * @return true once none is left; false when time ran out or {@code cancelled} turned true, and the gate stays
	 *         closed until {@link #open} either way
	 */
	synchronized boolean close(Duration timeout, BooleanSupplier cancelled) throws InterruptedException {
		closed = true;
		long deadline = System.nanoTime() + timeout.toNanos();
		while (active > 0) {
			long left = deadline - System.nanoTime();
			if (left <= 0 || cancelled.getAsBoolean()) {
				return false;
			}
			wait(Math.min(Math.max(1, left / 1_000_000), CHECK_MILLIS));
		}
		return true;
	}

	synchronized void open() {
		closed = false;
	}
}
