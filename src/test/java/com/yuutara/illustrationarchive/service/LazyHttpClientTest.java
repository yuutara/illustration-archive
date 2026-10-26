package com.yuutara.illustrationarchive.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class LazyHttpClientTest {

	@Test
	void createsTheClientOnFirstUseAndReusesIt() {
		try (MockedStatic<HttpClient> httpClients = mockStatic(HttpClient.class)) {
			HttpClient created = mock(HttpClient.class);
			httpClients.when(HttpClient::newHttpClient).thenReturn(created);

			LazyHttpClient lazy = new LazyHttpClient();
			httpClients.verify(HttpClient::newHttpClient, never());

			assertSame(created, lazy.get());
			assertSame(created, lazy.get());
			httpClients.verify(HttpClient::newHttpClient, times(1));
		}
	}
}
