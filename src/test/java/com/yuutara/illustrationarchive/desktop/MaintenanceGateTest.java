package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceGateTest {

	private final MaintenanceGate gate = new MaintenanceGate();

	@Test
	void closingWaitsForTheRequestInsideAndRefusesNewOnesMeanwhile() throws Exception {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> serve((request, response) -> {
			inside.countDown();
			await(release);
		}));
		assertTrue(inside.await(5, TimeUnit.SECONDS));

		CompletableFuture<Boolean> closing = CompletableFuture.supplyAsync(() -> close(Duration.ofSeconds(10)));
		Thread.sleep(300);
		assertFalse(closing.isDone(), "a request is still inside");
		MockHttpServletResponse refused = serve((request, response) -> { throw new AssertionError("must not run"); });
		assertEquals(503, refused.getStatus());

		release.countDown();
		assertTrue(closing.get(5, TimeUnit.SECONDS));
		slow.get(5, TimeUnit.SECONDS);
		gate.open();
		assertEquals(200, serve((request, response) -> { }).getStatus());
	}

	@Test
	void runningOutOfTimeOrCancellingIsReportedAndOpeningAgainLetsRequestsIn() throws Exception {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> serve((request, response) -> {
			inside.countDown();
			await(release);
		}));
		assertTrue(inside.await(5, TimeUnit.SECONDS));

		assertFalse(close(Duration.ofMillis(200)));
		assertFalse(gate.close(Duration.ofSeconds(10), () -> true), "a cancel ends the wait at once");
		gate.open();
		assertEquals(200, serve((request, response) -> { }).getStatus());
		release.countDown();
		slow.get(5, TimeUnit.SECONDS);
	}

	private boolean close(Duration timeout) {
		try {
			return gate.close(timeout, () -> false);
		} catch (InterruptedException interrupted) {
			throw new IllegalStateException(interrupted);
		}
	}

	private MockHttpServletResponse serve(jakarta.servlet.FilterChain chain) {
		MockHttpServletResponse response = new MockHttpServletResponse();
		try {
			gate.doFilter(new MockHttpServletRequest("GET", "/api/illustrations"), response, chain);
		} catch (Exception failed) {
			throw new IllegalStateException(failed);
		}
		return response;
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
