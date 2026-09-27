package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.XLikeInboxItem;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeSyncSummary;
import com.yuutara.illustrationarchive.service.XApiException;
import com.yuutara.illustrationarchive.service.XLikeSyncService;
import com.yuutara.illustrationarchive.service.XImportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class XImportControllerTest {
	@Test
	void importEndpointReturnsPerPostSummary() throws Exception {
		XImportService imports = mock(XImportService.class);
		when(imports.importSelected(List.of(1L, 2L))).thenReturn(new XImportService.Summary(2, 1, 1, 0,
				List.of(new XImportService.ItemResult(1, "SUCCESS", 20L, null),
						new XImportService.ItemResult(2, "DUPLICATE", null, "duplicate photo"))));
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new XImportController(mock(XLikeSyncService.class), imports)).build();
		mvc.perform(post("/api/x-import/inbox/import")
				.contentType("application/json").content("{\"itemIds\":[1,2]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.successCount").value(1))
				.andExpect(jsonPath("$.duplicateCount").value(1))
				.andExpect(jsonPath("$.items[0].illustrationId").value(20))
				.andExpect(jsonPath("$.items[1].status").value("DUPLICATE"));
		verify(imports).importSelected(List.of(1L, 2L));
	}

	@Test
	void syncEndpointReturnsSummaryAndUsesDefaultWhenSizeMissing() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);
		MockMvc mvc = mvc(service);
		when(service.syncRecent(null, null)).thenReturn(new XLikeSyncSummary(1, 1, 1, 0, 1, 0, true, true, false));

		mvc.perform(post("/api/x-import/sync/recent"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fetchedCount").value(1))
				.andExpect(jsonPath("$.newCount").value(1))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andExpect(jsonPath("$.pagesFetched").value(1))
				.andExpect(jsonPath("$.stoppedByMaxPages").value(true));
		verify(service).syncRecent(null, null);
	}

	@Test
	void invalidContinuationCanBeResetExplicitly() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);

		mvc(service).perform(post("/api/x-import/sync/continuation/reset"))
				.andExpect(status().isNoContent());
		verify(service).resetInvalidContinuation();
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
		String unauthorized = "X rejected the current access credential. Check the credential in X Developer, update the local configuration, restart the application, and retry.";
		String forbidden = "X denied this request. Possible causes include an invalid credential, a credential not valid for this endpoint, insufficient app or user permissions, or other access conditions. Check the credential and app/user permissions in X Developer.";
		when(service.syncRecent(4, null)).thenThrow(new IllegalArgumentException("maxResults must be between 5 and 100."));
		when(service.syncRecent(5, 0)).thenThrow(new IllegalArgumentException("maxPages must be between 1 and 10."));
		when(service.syncRecent(5, null)).thenThrow(new XApiException(unauthorized, 401));
		when(service.syncRecent(6, null)).thenThrow(new XApiException("X API rate limit reached; retry manually later.", 429));
		when(service.syncRecent(7, null)).thenThrow(new XApiException(forbidden, 403));
		when(service.syncRecent(8, null)).thenThrow(new XApiException("X API request failed with HTTP 500.", 500));

		mvc.perform(post("/api/x-import/sync/recent?maxResults=4"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=5&maxPages=0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("maxPages must be between 1 and 10."));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=5"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("X_CREDENTIAL_REJECTED"))
				.andExpect(jsonPath("$.upstreamStatus").value(401))
				.andExpect(jsonPath("$.message").value(unauthorized))
				.andExpect(content().string(not(containsString("test-secret"))))
				.andExpect(content().string(not(containsString("Authorization"))));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=7"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("X_ACCESS_DENIED"))
				.andExpect(jsonPath("$.upstreamStatus").value(403))
				.andExpect(jsonPath("$.message").value(forbidden))
				.andExpect(content().string(not(containsString("test-secret"))))
				.andExpect(content().string(not(containsString("Authorization"))));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=6"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("X_API_ERROR"))
				.andExpect(jsonPath("$.upstreamStatus").value(429));
		mvc.perform(post("/api/x-import/sync/recent?maxResults=8"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("X_API_ERROR"))
				.andExpect(jsonPath("$.upstreamStatus").value(500));
	}

	@Test
	void skipEndpointReturnsActualCount() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);
		when(service.skip(List.of(1L, 2L, 99L)))
				.thenReturn(new XLikeSyncService.SkipSummary(3, 2));

		mvc(service).perform(patch("/api/x-import/inbox/skip")
				.contentType("application/json")
				.content("{\"itemIds\":[1,2,99]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestedCount").value(3))
				.andExpect(jsonPath("$.skippedCount").value(2));
	}

	@Test
	void emptyIdsReturnClearBadRequest() throws Exception {
		XLikeSyncService service = mock(XLikeSyncService.class);
		when(service.skip(List.of())).thenThrow(new IllegalArgumentException(
				"itemIds must contain at least one positive item id."));

		mvc(service).perform(patch("/api/x-import/inbox/skip")
				.contentType("application/json")
				.content("{\"itemIds\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.message").value("itemIds must contain at least one positive item id."));
	}

	private MockMvc mvc(XLikeSyncService service) {
		return MockMvcBuilders.standaloneSetup(new XImportController(service, mock(XImportService.class))).build();
	}
}
