package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AssetSummary;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.IllustrationDetail;
import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.service.IllustrationDetailService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IllustrationDetailControllerTest {

	@Test
	void returnsIllustrationDetailFromService() throws Exception {
		IllustrationDetailService illustrationDetailService = mock(IllustrationDetailService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationDetailController(illustrationDetailService))
				.build();
		IllustrationDetail detail = new IllustrationDetail(
				3L,
				"Sunset",
				new AuthorSummary(2L, "Artist", "artist_x"),
				"https://example.com/source",
				"Reference note",
				List.of(new AssetSummary(10L, "sunset.jpg", "image/jpeg", 123L, 0)),
				List.of(new TagSummary(5L, "landscape")),
				LocalDateTime.of(2026, 9, 19, 15, 0),
				LocalDateTime.of(2026, 9, 19, 16, 0)
		);
		when(illustrationDetailService.getDetail(3L)).thenReturn(detail);

		mockMvc.perform(get("/api/illustrations/3"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(3))
				.andExpect(jsonPath("$.title").value("Sunset"))
				.andExpect(jsonPath("$.author.id").value(2))
				.andExpect(jsonPath("$.author.displayName").value("Artist"))
				.andExpect(jsonPath("$.author.xUsername").value("artist_x"))
				.andExpect(jsonPath("$.sourceUrl").value("https://example.com/source"))
				.andExpect(jsonPath("$.note").value("Reference note"))
				.andExpect(jsonPath("$.assets[0].id").value(10))
				.andExpect(jsonPath("$.assets[0].originalFilename").value("sunset.jpg"))
				.andExpect(jsonPath("$.assets[0].mimeType").value("image/jpeg"))
				.andExpect(jsonPath("$.assets[0].fileSize").value(123))
				.andExpect(jsonPath("$.assets[0].sortOrder").value(0))
				.andExpect(jsonPath("$.tags[0].id").value(5))
				.andExpect(jsonPath("$.tags[0].name").value("landscape"))
				.andExpect(jsonPath("$.createdAt").value("2026-09-19T15:00:00"))
				.andExpect(jsonPath("$.updatedAt").value("2026-09-19T16:00:00"));

		verify(illustrationDetailService).getDetail(3L);
	}
}
