package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationDeleteService;
import com.yuutara.illustrationarchive.service.IllustrationNotFoundException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IllustrationDeleteControllerTest {

	private final IllustrationDeleteService illustrationDeleteService = mock(IllustrationDeleteService.class);
	private final MockMvc mockMvc = MockMvcBuilders
			.standaloneSetup(new IllustrationDeleteController(illustrationDeleteService))
			.build();

	@Test
	void deletesIllustrationAndReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/illustrations/3"))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(illustrationDeleteService).delete(3L);
	}

	@Test
	void missingIllustrationIsNotFound() throws Exception {
		doThrow(new IllustrationNotFoundException(9)).when(illustrationDeleteService).delete(9L);

		mockMvc.perform(delete("/api/illustrations/9"))
				.andExpect(status().isNotFound());
	}

	@Test
	void otherFailuresAreNotTurnedIntoNotFound() {
		doThrow(new IllegalStateException("database is down")).when(illustrationDeleteService).delete(4L);

		// Unmapped, so the servlet container answers 500 as before
		assertThrows(ServletException.class, () -> mockMvc.perform(delete("/api/illustrations/4")));
	}
}
