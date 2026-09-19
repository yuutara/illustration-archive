package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.AssetContent;
import com.yuutara.illustrationarchive.service.AssetContentService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetContentControllerTest {

	@Test
	void returnsAssetResourceWithContentHeaders() throws Exception {
		AssetContentService assetContentService = mock(AssetContentService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new AssetContentController(assetContentService))
				.build();
		byte[] imageBytes = {1, 2, 3, 4};
		when(assetContentService.load(3L)).thenReturn(new AssetContent(
				new ByteArrayResource(imageBytes),
				"image/jpeg",
				imageBytes.length
		));

		mockMvc.perform(get("/api/assets/3/content"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("image/jpeg"))
				.andExpect(header().string("Content-Length", String.valueOf(imageBytes.length)))
				.andExpect(content().bytes(imageBytes));

		verify(assetContentService).load(3L);
	}
}
