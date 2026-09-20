package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.service.AuthorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthorControllerTest {

	@Test
	void createsAuthorAndReturnsCreatedDetail() throws Exception {
		AuthorService authorService = mock(AuthorService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new AuthorController(authorService))
				.build();
		AuthorDetail detail = new AuthorDetail(
				7L,
				"Artist",
				"@artist",
				LocalDateTime.of(2026, 9, 20, 10, 0),
				LocalDateTime.of(2026, 9, 20, 11, 0)
		);
		when(authorService.create("Artist", "@artist")).thenReturn(detail);

		mockMvc.perform(post("/api/authors")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "displayName": "Artist",
							  "xUsername": "@artist"
							}
							"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.displayName").value("Artist"))
				.andExpect(jsonPath("$.xUsername").value("@artist"))
				.andExpect(jsonPath("$.createdAt").value("2026-09-20T10:00:00"))
				.andExpect(jsonPath("$.updatedAt").value("2026-09-20T11:00:00"));

		verify(authorService).create("Artist", "@artist");
	}

	@Test
	void searchesAuthorsByKeyword() throws Exception {
		AuthorService authorService = mock(AuthorService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new AuthorController(authorService))
				.build();
		when(authorService.search("kudo"))
				.thenReturn(List.of(new AuthorSummary(7L, "Kudo", "@kudo")));

		mockMvc.perform(get("/api/authors?keyword=kudo"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(7))
				.andExpect(jsonPath("$[0].displayName").value("Kudo"))
				.andExpect(jsonPath("$[0].xUsername").value("@kudo"));

		verify(authorService).search("kudo");
	}

	@Test
	void passesNullKeywordWhenSearchParameterIsAbsent() throws Exception {
		AuthorService authorService = mock(AuthorService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new AuthorController(authorService))
				.build();
		when(authorService.search(null)).thenReturn(List.of());

		mockMvc.perform(get("/api/authors"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").isEmpty());

		verify(authorService).search(null);
	}
}
