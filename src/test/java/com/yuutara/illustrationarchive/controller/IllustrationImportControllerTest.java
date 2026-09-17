package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationImportResult;
import com.yuutara.illustrationarchive.service.IllustrationImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IllustrationImportControllerTest {

	@Test
	void importsMultipartFileAndReturnsImportResult() throws Exception {
		IllustrationImportService illustrationImportService = mock(IllustrationImportService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationImportController(illustrationImportService))
				.build();
		MockMultipartFile file = new MockMultipartFile(
				"file",
				"example.jpg",
				"image/jpeg",
				new byte[]{1, 2, 3}
		);

		when(illustrationImportService.importSingle(any()))
				.thenReturn(new IllustrationImportResult(10L, 20L));

		mockMvc.perform(multipart("/api/illustrations/import").file(file))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.illustrationId").value(10))
				.andExpect(jsonPath("$.assetId").value(20));

		verify(illustrationImportService).importSingle(any());
	}
}
