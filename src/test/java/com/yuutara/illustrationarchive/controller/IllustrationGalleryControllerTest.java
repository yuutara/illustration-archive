package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.dto.GalleryAsset;
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
	private static final IllustrationGalleryQuery EMPTY = new IllustrationGalleryQuery(null, null, null);

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
				List.of(
						new IllustrationGalleryItem(10L, "Sunset",
								new AuthorSummary(2L, "Artist", "artist_x"), 20L, "image/jpeg", 2,
								LocalDateTime.of(2026, 9, 19, 12, 30), List.of(
										new GalleryAsset(20L, "image/jpeg", 0), new GalleryAsset(23L, "image/png", 1))),
						new IllustrationGalleryItem(11L, "PNG", null, 21L, "image/png", 1,
								LocalDateTime.of(2026, 9, 19, 12, 31), List.of(new GalleryAsset(21L, "image/png", 0))),
						new IllustrationGalleryItem(12L, "GIF", null, 22L, "image/gif", 1,
								LocalDateTime.of(2026, 9, 19, 12, 32), List.of(new GalleryAsset(22L, "image/gif", 0)))
				), null
		);
		when(illustrationGalleryService.getGallery(1, 10, EMPTY)).thenReturn(galleryPage);

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
				.andExpect(jsonPath("$.items[0].coverMimeType").value("image/jpeg"))
				.andExpect(jsonPath("$.items[1].coverAssetId").value(21))
				.andExpect(jsonPath("$.items[1].coverMimeType").value("image/png"))
				.andExpect(jsonPath("$.items[2].coverAssetId").value(22))
				.andExpect(jsonPath("$.items[2].coverMimeType").value("image/gif"))
				.andExpect(jsonPath("$.items[0].assetCount").value(2))
				.andExpect(jsonPath("$.items[0].assets[0].id").value(20))
				.andExpect(jsonPath("$.items[0].assets[1].mimeType").value("image/png"))
				.andExpect(jsonPath("$.items[0].assets[1].sortOrder").value(1))
				.andExpect(jsonPath("$.items[1].assets.length()").value(1))
				.andExpect(jsonPath("$.items[0].createdAt").value("2026-09-19T12:30:00"));

		verify(illustrationGalleryService).getGallery(1, 10, EMPTY);
	}

	@Test
	void usesDefaultPaginationParametersWhenTheyAreNotProvided() throws Exception {
		IllustrationGalleryService illustrationGalleryService = mock(IllustrationGalleryService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationGalleryController(illustrationGalleryService))
				.build();
		when(illustrationGalleryService.getGallery(0, 24, EMPTY))
				.thenReturn(new IllustrationGalleryPage(0, 24, 0L, 0, List.of(), null));

		mockMvc.perform(get("/api/illustrations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(24));

		verify(illustrationGalleryService).getGallery(0, 24, EMPTY);
	}

	@Test
	void bindsAndNormalizesSearchAndExactIds() throws Exception {
		var service = mock(IllustrationGalleryService.class);
		var query = new IllustrationGalleryQuery("夏日", 12L, 7L);
		when(service.getGallery(0, 24, query)).thenReturn(new IllustrationGalleryPage(0, 24, 0, 0, List.of(), null));
		var mvc = MockMvcBuilders.standaloneSetup(new IllustrationGalleryController(service)).build();
		mvc.perform(get("/api/illustrations").param("q", "  夏日  ").param("authorId", "12").param("tagId", "7"))
				.andExpect(status().isOk());
		verify(service).getGallery(0, 24, query);
	}

	@Test
	void rejectsMalformedIdsAndSearchTooLongWithBadRequest() throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(new IllustrationGalleryController(mock(IllustrationGalleryService.class))).build();
		for (String value : List.of("0", "-1", "wrong", "9223372036854775808")) {
			mvc.perform(get("/api/illustrations").param("authorId", value)).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/illustrations").param("q", "x".repeat(201)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}
}
