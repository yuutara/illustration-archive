package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AiAnalysisResponse;
import com.yuutara.illustrationarchive.service.IllustrationAiAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IllustrationAiAnalysisControllerTest {
	private final IllustrationAiAnalysisService service = mock(IllustrationAiAnalysisService.class);
	private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
			.standaloneSetup(new IllustrationAiAnalysisController(service)).build();

	@Test
	void returnsEphemeralResultWithServerMediaMappings() throws Exception {
		when(service.analyze(7)).thenReturn(new AiAnalysisResponse("摘要", List.of(new AiAnalysisResponse.Page(1, "描述",
				List.of(new AiAnalysisResponse.PageText("Hello", "你好", "右上对话框")))),
				List.of(), List.of(), List.of("漫画"), "free", List.of(new AiAnalysisResponse.Media(11, 1, "image/jpeg")),
				List.of(new AiAnalysisResponse.Media(12, 2, "video/mp4"))));
		mvc.perform(post("/api/illustrations/7/ai-analysis")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.summary").value("摘要"))
				.andExpect(jsonPath("$.pages[0].index").value(1))
				.andExpect(jsonPath("$.pages[0].texts[0].source").value("Hello"))
				.andExpect(jsonPath("$.pages[0].texts[0].translation").value("你好"))
				.andExpect(jsonPath("$.pages[0].texts[0].note").value("右上对话框"))
				.andExpect(jsonPath("$.suggestedTags[0]").value("漫画"))
				.andExpect(jsonPath("$.skippedAssets[0].assetId").value(12));
		verify(service).analyze(7);
	}

	@Test
	void returnsSafeMessagesForSupportedFailures() throws Exception {
		for (int code : List.of(404, 422, 429, 502, 503, 504)) {
			doThrow(new ResponseStatusException(HttpStatus.valueOf(code), "安全提示")).when(service).analyze(7);
			mvc.perform(post("/api/illustrations/7/ai-analysis")).andExpect(status().is(code))
					.andExpect(jsonPath("$.message").value("安全提示"))
					.andExpect(header().string("Cache-Control", "no-store"));
		}
	}
}
