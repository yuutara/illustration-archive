package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.service.TagService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TagControllerTest {

	@Test
	void createsTagAndReturnsCreatedSummary() throws Exception {
		TagService tagService = mock(TagService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new TagController(tagService))
				.build();
		when(tagService.create("百合")).thenReturn(new TagSummary(1L, "百合"));

		mockMvc.perform(post("/api/tags")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\":\"百合\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.name").value("百合"));

		verify(tagService).create("百合");
	}

	@Test
	void searchesTagsByKeyword() throws Exception {
		TagService tagService = mock(TagService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new TagController(tagService))
				.build();
		when(tagService.search("百")).thenReturn(List.of(new TagSummary(1L, "百合")));

		mockMvc.perform(get("/api/tags").queryParam("keyword", "百"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(1))
				.andExpect(jsonPath("$[0].name").value("百合"));

		verify(tagService).search("百");
	}

	@Test
	void passesNullKeywordAndReturnsEmptyArrayWhenParameterIsAbsent() throws Exception {
		TagService tagService = mock(TagService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new TagController(tagService))
				.build();
		when(tagService.search(null)).thenReturn(List.of());

		mockMvc.perform(get("/api/tags"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").isEmpty());

		verify(tagService).search(null);
	}
}
