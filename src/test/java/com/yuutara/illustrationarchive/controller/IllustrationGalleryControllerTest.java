package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.service.IllustrationGalleryService;
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

class IllustrationGalleryControllerTest {

	@Test
	void passesQueryParametersToGalleryServiceAndReturnsGalleryPage() throws Exception {
		IllustrationGalleryService illustrationGalleryService = mock(IllustrationGalleryService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationGalleryController(illustrationGalleryService))
				.build();
		IllustrationGalleryPage galleryPage = new IllustrationGalleryPage(
				1,
				10,
				21L,
				3,
				List.of(new IllustrationGalleryItem(
						10L,
						"Sunset",
						new AuthorSummary(2L, "Artist", "artist_x"),
						20L,
						2,
						LocalDateTime.of(2026, 9, 19, 12, 30)
				))
		);
		when(illustrationGalleryService.getGallery(1, 10)).thenReturn(galleryPage);

		mockMvc.perform(get("/api/illustrations?page=1&size=10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(10))
				.andExpect(jsonPath("$.totalElements").value(21))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items[0].id").value(10))
				.andExpect(jsonPath("$.items[0].title").value("Sunset"))
				.andExpect(jsonPath("$.items[0].author.id").value(2))
				.andExpect(jsonPath("$.items[0].author.displayName").value("Artist"))
				.andExpect(jsonPath("$.items[0].author.xUsername").value("artist_x"))
				.andExpect(jsonPath("$.items[0].coverAssetId").value(20))
				.andExpect(jsonPath("$.items[0].assetCount").value(2))
				.andExpect(jsonPath("$.items[0].createdAt").value("2026-09-19T12:30:00"));

		verify(illustrationGalleryService).getGallery(1, 10);
	}

	@Test
	void usesDefaultPaginationParametersWhenTheyAreNotProvided() throws Exception {
		IllustrationGalleryService illustrationGalleryService = mock(IllustrationGalleryService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationGalleryController(illustrationGalleryService))
				.build();
		when(illustrationGalleryService.getGallery(0, 24))
				.thenReturn(new IllustrationGalleryPage(0, 24, 0L, 0, List.of()));

		mockMvc.perform(get("/api/illustrations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(24));

		verify(illustrationGalleryService).getGallery(0, 24);
	}
}
