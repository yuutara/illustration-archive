package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.service.XApiException;
import com.yuutara.illustrationarchive.service.XLikeSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class XImportControllerTest {
	@Test
	void syncEndpointReturnsSummaryAndUsesDefaultWhenSizeMissing() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);
		MockMvc mvc = mvc(service);
		when(service.syncRecent(null)).thenReturn(new XLikeSyncSummary(1, 1, 0, 1, 0, true));

		mvc.perform(post("/api/x-import/sync/recent"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fetchedCount").value(1))
				.andExpect(jsonPath("$.newCount").value(1))
				.andExpect(jsonPath("$.hasMore").value(true));
		verify(service).syncRecent(null);
	}

	@Test
	void inboxReturnsOrderedMediaAndPostTime() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);
		MockMvc mvc = mvc(service);
		when(service.inbox()).thenReturn(List.of(new XLikeInboxItem(7L, "11", "Artist", "artist",
				"text", Instant.parse("2026-09-25T09:00:00Z"),
				List.of(new XLikeMedia("p1", 0, "photo", "https://img/1", 100, 200)))));

		mvc.perform(get("/api/x-import/inbox"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(7))
				.andExpect(jsonPath("$[0].xPostId").value("11"))
				.andExpect(jsonPath("$[0].authorDisplayName").value("Artist"))
				.andExpect(jsonPath("$[0].authorUsername").value("artist"))
				.andExpect(jsonPath("$[0].postText").value("text"))
				.andExpect(jsonPath("$[0].postCreatedAt").value("2026-09-25T09:00:00Z"))
				.andExpect(jsonPath("$[0].media[0].sortOrder").value(0))
				.andExpect(jsonPath("$[0].media[0].photoUrl").value("https://img/1"))
				.andExpect(jsonPath("$[0].media[0].width").value(100));
	}

	@Test
	void mapsValidationAndUpstreamErrorsWithoutSecret() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);
		MockMvc mvc = mvc(service);
		when(service.syncRecent(4)).thenThrow(new IllegalArgumentException("maxResults must be between 5 and 100."));
		when(service.syncRecent(5)).thenThrow(new XApiException("X API rejected the access token or its permissions.", 401));
		when(service.syncRecent(6)).thenThrow(new XApiException("X API rate limit reached; retry manually later.", 429));

		mvc.perform(post("/api/x-import/sync/recent?maxResults=4"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=5"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.upstreamStatus").value(401))
				.andExpect(jsonPath("$.message").value("X API rejected the access token or its permissions."));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=6"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.upstreamStatus").value(429));
	}

	private MockMvc mvc(XLikeSyncService service) {
		return MockMvcBuilders.standaloneSetup(new XImportController(service)).build();
	}
}
