package com.yuutara.illustrationarchive.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class XApiClientSpringContextTest {
	@Test
	void springInstantiatesClientWithItsAnnotatedConstructor() {
		// Creating a JDK HttpClient may open a local loopback connection on some Windows/JDK versions.
		// Only stub that factory; Spring still selects and invokes the real @Autowired constructor.
		try (MockedStatic<HttpClient> httpClients = mockStatic(HttpClient.class);
				AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			httpClients.when(HttpClient::newHttpClient).thenReturn(mock(HttpClient.class));
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(XApiClient.class);
			context.refresh();

			assertNotNull(context.getBean(XApiClient.class));
			httpClients.verify(HttpClient::newHttpClient);
		}
	}
}
