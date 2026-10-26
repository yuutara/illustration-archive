package com.yuutara.illustrationarchive.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

class XApiClientSpringContextTest {
	@Test
	void springInstantiatesClientWithItsAnnotatedConstructorWithoutCreatingHttpClients() {
		// Creating a JDK HttpClient may open a local loopback connection on some Windows/JDK versions,
		// so disabled integrations must not create one at startup. The stub only guards against regressions.
		try (MockedStatic<HttpClient> httpClients = mockStatic(HttpClient.class);
				AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			httpClients.when(HttpClient::newHttpClient).thenReturn(mock(HttpClient.class));
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(XTokenCredentialStore.class, XTokenManager.class, XApiClient.class);
			context.refresh();

			assertNotNull(context.getBean(XApiClient.class));
			assertNotNull(context.getBean(XTokenManager.class));
			httpClients.verify(HttpClient::newHttpClient, never());
		}
	}
}
