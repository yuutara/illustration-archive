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

class ThumbnailBackfillRunnerTest {

	@Test
	void missingPropertyLeavesRunnerDisabled() {
		try (AnnotationConfigApplicationContext context = createContext(null)) {
			ThumbnailBackfillService service = context.getBean(ThumbnailBackfillService.class);
			assertTrue(context.getBeansOfType(ThumbnailBackfillRunner.class).isEmpty());
			verify(service, never()).backfillThumbnails();
		}
	}

	@Test
	void falsePropertyLeavesRunnerDisabled() {
		try (AnnotationConfigApplicationContext context = createContext("false")) {
			ThumbnailBackfillService service = context.getBean(ThumbnailBackfillService.class);
			assertTrue(context.getBeansOfType(ThumbnailBackfillRunner.class).isEmpty());
			verify(service, never()).backfillThumbnails();
		}
	}

	@Test
	void truePropertyExecutesBackfillAndReportsSummary() throws Exception {
		try (AnnotationConfigApplicationContext context = createContext("true")) {
			ThumbnailBackfillService service = context.getBean(ThumbnailBackfillService.class);
			when(service.backfillThumbnails()).thenReturn(List.of(
					ThumbnailBackfillResult.ready(10L, "2026-08/ready.jpg"),
					ThumbnailBackfillResult.ready(11L, "2026-08/ready.png"),
					ThumbnailBackfillResult.skippedGif(15L, "2026-08/animated.gif"),
					ThumbnailBackfillResult.failure(20L, "2026-09/missing.png", "file missing")
			));
			CommandLineRunner runner = context.getBean(ThumbnailBackfillRunner.class);
			ListAppender<ILoggingEvent> appender = attachAppender();
			try {
				runner.run();
			} finally {
				detachAppender(appender);
			}

			verify(service).backfillThumbnails();
			assertEquals(1, appender.list.stream()
					.filter(event -> event.getFormattedMessage()
							.contains("total=4, ready=2, skippedGif=1, failed=1"))
					.count());
		}
	}

	@Test
	void repositoryOrServiceFailureSurfacesFromRunner() {
		try (AnnotationConfigApplicationContext context = createContext("true")) {
			ThumbnailBackfillService service = context.getBean(ThumbnailBackfillService.class);
			IllegalStateException expected = new IllegalStateException("database unavailable");
			when(service.backfillThumbnails()).thenThrow(expected);
			CommandLineRunner runner = context.getBean(ThumbnailBackfillRunner.class);

			assertSame(expected, assertThrows(IllegalStateException.class, () -> runner.run()));
			verify(service).backfillThumbnails();
		}
	}

	private AnnotationConfigApplicationContext createContext(String propertyValue) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		if (propertyValue != null) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"test-properties",
					Map.of("illustration.maintenance.thumbnail-backfill", propertyValue)
			));
		}
		context.register(TestConfiguration.class);
		context.refresh();
		return context;
	}

	private ListAppender<ILoggingEvent> attachAppender() {
		Logger logger = (Logger) LoggerFactory.getLogger(ThumbnailBackfillRunner.class);
		logger.setLevel(Level.INFO);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(ThumbnailBackfillRunner.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	@Configuration(proxyBeanMethods = false)
	@Import(ThumbnailBackfillRunner.class)
	static class TestConfiguration {

		@Bean
		ThumbnailBackfillService thumbnailBackfillService() {
			return mock(ThumbnailBackfillService.class);
		}
	}
}
