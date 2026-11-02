package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.repository.XLikeSyncStateRepository;
import com.yuutara.illustrationarchive.service.XTokenManager;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one fact the integration tests cannot show: they must never open a real browser on x.com. */
class XAuthorizationServiceTest {

	@Test
	void statusSaysWhetherTheBrowserReallyOpened() {
		XTokenManager tokens = mock(XTokenManager.class);
		when(tokens.savedLogin()).thenReturn(XTokenManager.SavedLogin.NONE);
		DesktopExternal external = mock(DesktopExternal.class);
		XAuthorizationService service = new XAuthorizationService(tokens, mock(DesktopSettings.class), external,
				mock(XLikeSyncStateRepository.class), mock(DesktopEvidence.class), "http://127.0.0.1/authorize", 0, 300);
		try {
			when(external.browse(any(URI.class))).thenReturn(true);
			XAuthorizationService.Status opened = service.start("client-id-123");
			assertEquals(XAuthorizationService.State.WAITING, opened.state());
			assertTrue(opened.browserOpened());
			assertTrue(opened.authorizeUrl().startsWith("http://127.0.0.1/authorize?"), opened.authorizeUrl());

			when(external.browse(any(URI.class))).thenReturn(false);
			assertFalse(service.start("client-id-123").browserOpened(), "the page then offers the link to copy");
		} finally {
			service.abandonAttempt();
		}
	}
}
