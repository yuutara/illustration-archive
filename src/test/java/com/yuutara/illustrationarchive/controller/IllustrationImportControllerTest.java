package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationBatchImportItemResult;
import com.yuutara.illustrationarchive.service.IllustrationBatchImportResult;
import com.yuutara.illustrationarchive.service.IllustrationBatchImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IllustrationImportControllerTest {

	@Test
	void importsMultipleFilesAndReturnsBatchImportResult() throws Exception {
		IllustrationBatchImportService illustrationBatchImportService = mock(IllustrationBatchImportService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationImportController(illustrationBatchImportService))
				.build();
		MockMultipartFile firstFile = new MockMultipartFile(
				"files",
				"first.jpg",
				"image/jpeg",
				new byte[]{1, 2, 3}
		);
		MockMultipartFile secondFile = new MockMultipartFile(
				"files",
				"second.png",
				"image/png",
				new byte[]{4, 5, 6}
		);
		IllustrationBatchImportResult batchResult = new IllustrationBatchImportResult(
				2,
				1,
				1,
				List.of(
						new IllustrationBatchImportItemResult("first.jpg", true, 10L, null, null),
						new IllustrationBatchImportItemResult(
								"second.png", false, null, "INVALID_FILE", "Unsupported image format."
						)
				)
		);

		when(illustrationBatchImportService.importBatch(anyList())).thenReturn(batchResult);

		mockMvc.perform(multipart("/api/illustrations/import").file(firstFile).file(secondFile))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.successCount").value(1))
				.andExpect(jsonPath("$.failureCount").value(1))
				.andExpect(jsonPath("$.items[0].filename").value("first.jpg"))
				.andExpect(jsonPath("$.items[0].success").value(true))
				.andExpect(jsonPath("$.items[0].illustrationId").value(10))
				.andExpect(jsonPath("$.items[1].filename").value("second.png"))
				.andExpect(jsonPath("$.items[1].success").value(false))
				.andExpect(jsonPath("$.items[1].errorCode").value("INVALID_FILE"))
				.andExpect(jsonPath("$.items[1].message").value("Unsupported image format."));

		@SuppressWarnings({"unchecked", "rawtypes"})
		ArgumentCaptor<List<MultipartFile>> filesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
		verify(illustrationBatchImportService).importBatch(filesCaptor.capture());
		assertEquals(
				List.of("first.jpg", "second.png"),
				filesCaptor.getValue().stream().map(MultipartFile::getOriginalFilename).toList()
		);
	}
}
