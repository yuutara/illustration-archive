package com.yuutara.illustrationarchive.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UiCapabilitiesControllerTest {

	@Test
	void servesTheConfiguredFlagsAsAnUncachedScript() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new UiCapabilitiesController(true, false)).build();

		mockMvc.perform(get("/ui-capabilities.js"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", containsString("text/javascript")))
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(content().string("window.UiCapabilities = Object.freeze({ xImport: true, aiAnalysis: false });\n"));
	}
}
