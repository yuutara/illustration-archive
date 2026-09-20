package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationDeleteService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IllustrationDeleteControllerTest {

	@Test
	void deletesIllustrationAndReturnsNoContent() throws Exception {
		IllustrationDeleteService illustrationDeleteService = mock(IllustrationDeleteService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationDeleteController(illustrationDeleteService))
				.build();

		mockMvc.perform(delete("/api/illustrations/3"))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(illustrationDeleteService).delete(3L);
	}
}
