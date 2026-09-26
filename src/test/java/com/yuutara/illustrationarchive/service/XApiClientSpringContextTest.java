package com.yuutara.illustrationarchive.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class XApiClientSpringContextTest {
	@Test
	void springInstantiatesClientWithItsAnnotatedConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(XApiClient.class);
			context.refresh();

			assertNotNull(context.getBean(XApiClient.class));
		}
	}
}
