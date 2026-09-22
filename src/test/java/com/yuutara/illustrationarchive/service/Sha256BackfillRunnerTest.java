package com.yuutara.illustrationarchive.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sha256BackfillRunnerTest {

	@Test
	void missingPropertyDoesNotCreateOrExecuteRunner() {
		try (AnnotationConfigApplicationContext context = createContext(null)) {
			Sha256BackfillService service = context.getBean(Sha256BackfillService.class);

			assertTrue(context.getBeansOfType(Sha256BackfillRunner.class).isEmpty());
			verify(service, never()).backfillPendingSha256();
		}
	}

	@Test
	void falsePropertyDoesNotCreateOrExecuteRunner() {
		try (AnnotationConfigApplicationContext context = createContext("false")) {
			Sha256BackfillService service = context.getBean(Sha256BackfillService.class);

			assertTrue(context.getBeansOfType(Sha256BackfillRunner.class).isEmpty());
			verify(service, never()).backfillPendingSha256();
		}
	}

	@Test
	void truePropertyCreatesRunnerAndExecutesBackfillOnceWithSummary() throws Exception {
		try (AnnotationConfigApplicationContext context = createContext("true")) {
			Sha256BackfillService service = context.getBean(Sha256BackfillService.class);
			when(service.backfillPendingSha256()).thenReturn(List.of(
					Sha256BackfillResult.updated(10L, "2026-08/updated.jpg", "a".repeat(64)),
					Sha256BackfillResult.duplicate(20L, "2026-08/duplicate.jpg", "b".repeat(64), 30L),
					Sha256BackfillResult.failure(40L, "2026-08/missing.jpg", "file missing")
			));

			CommandLineRunner runner = context.getBean(Sha256BackfillRunner.class);
			ListAppender<ILoggingEvent> appender = attachAppender();
			try {
				runner.run();
			} finally {
				detachAppender(appender);
			}

			verify(service).backfillPendingSha256();
			assertEquals(1, appender.list.stream()
					.filter(event -> event.getFormattedMessage().contains("total=3, updated=1, duplicate=1, failed=1"))
					.count());
			assertTrue(appender.list.stream()
					.anyMatch(event -> event.getFormattedMessage().contains("assetId=20, existingAssetId=30")));
			assertTrue(appender.list.stream()
					.anyMatch(event -> event.getFormattedMessage().contains("assetId=40, storageKey=2026-08/missing.jpg")));
		}
	}

	@Test
	void serviceRuntimeExceptionPropagatesFromRunner() {
		try (AnnotationConfigApplicationContext context = createContext("true")) {
			Sha256BackfillService service = context.getBean(Sha256BackfillService.class);
			IllegalStateException expected = new IllegalStateException("database unavailable");
			when(service.backfillPendingSha256()).thenThrow(expected);

			CommandLineRunner runner = context.getBean(Sha256BackfillRunner.class);

			IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> runner.run());

			assertSame(expected, thrown);
			verify(service).backfillPendingSha256();
		}
	}

	private AnnotationConfigApplicationContext createContext(String propertyValue) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		if (propertyValue != null) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"test-properties",
					Map.of("illustration.maintenance.sha256-backfill", propertyValue)
			));
		}
		context.register(TestConfiguration.class);
		context.refresh();
		return context;
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(Sha256BackfillRunner.class);
		logger.setLevel(Level.INFO);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(Sha256BackfillRunner.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	@Configuration(proxyBeanMethods = false)
	@Import(Sha256BackfillRunner.class)
	static class TestConfiguration {

		@Bean
		Sha256BackfillService sha256BackfillService() {
			return mock(Sha256BackfillService.class);
		}
	}
}
