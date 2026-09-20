package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.service.IllustrationUpdateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IllustrationUpdateControllerTest {

	@Test
	void mapsOrdinaryJsonValuesToPresentPatchFields() throws Exception {
		IllustrationUpdateService illustrationUpdateService = mock(IllustrationUpdateService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationUpdateController(illustrationUpdateService))
				.build();

		mockMvc.perform(patch("/api/illustrations/3")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "title": "A",
							  "note": "B"
							}
							"""))
				.andExpect(status().isNoContent());

		IllustrationPatchRequest request = capturedRequest(illustrationUpdateService);
		assertTrue(request.titlePresent());
		assertEquals("A", request.title());
		assertTrue(request.notePresent());
		assertEquals("B", request.note());
		assertFalse(request.sourceUrlPresent());
	}

	@Test
	void mapsExplicitJsonNullToPresentFieldWithNullValue() throws Exception {
		IllustrationUpdateService illustrationUpdateService = mock(IllustrationUpdateService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationUpdateController(illustrationUpdateService))
				.build();

		mockMvc.perform(patch("/api/illustrations/3")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "title": null
							}
							"""))
				.andExpect(status().isNoContent());

		IllustrationPatchRequest request = capturedRequest(illustrationUpdateService);
		assertTrue(request.titlePresent());
		assertNull(request.title());
	}

	@Test
	void leavesAllFieldsAbsentWhenJsonObjectHasNoSupportedFields() throws Exception {
		IllustrationUpdateService illustrationUpdateService = mock(IllustrationUpdateService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationUpdateController(illustrationUpdateService))
				.build();

		mockMvc.perform(patch("/api/illustrations/3")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{}"))
				.andExpect(status().isNoContent());

		IllustrationPatchRequest request = capturedRequest(illustrationUpdateService);
		assertFalse(request.titlePresent());
		assertFalse(request.sourceUrlPresent());
		assertFalse(request.notePresent());
	}

	@Test
	void mapsAuthorIdValueToPresentLongField() throws Exception {
		IllustrationUpdateService illustrationUpdateService = mock(IllustrationUpdateService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationUpdateController(illustrationUpdateService))
				.build();

		mockMvc.perform(patch("/api/illustrations/3")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"authorId\":1}"))
				.andExpect(status().isNoContent());

		IllustrationPatchRequest request = capturedRequest(illustrationUpdateService);
		assertTrue(request.authorIdPresent());
		assertEquals(1L, request.authorId());
	}

	@Test
	void mapsExplicitNullAuthorIdToPresentNullField() throws Exception {
		IllustrationUpdateService illustrationUpdateService = mock(IllustrationUpdateService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationUpdateController(illustrationUpdateService))
				.build();

		mockMvc.perform(patch("/api/illustrations/3")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"authorId\":null}"))
				.andExpect(status().isNoContent());

		IllustrationPatchRequest request = capturedRequest(illustrationUpdateService);
		assertTrue(request.authorIdPresent());
		assertNull(request.authorId());
	}

	@Test
	void leavesAuthorIdAbsentWhenItIsMissingFromJson() throws Exception {
		IllustrationUpdateService illustrationUpdateService = mock(IllustrationUpdateService.class);
		MockMvc mockMvc = MockMvcBuilders
				.standaloneSetup(new IllustrationUpdateController(illustrationUpdateService))
				.build();

		mockMvc.perform(patch("/api/illustrations/3")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"title\":\"A\"}"))
				.andExpect(status().isNoContent());

		IllustrationPatchRequest request = capturedRequest(illustrationUpdateService);
		assertFalse(request.authorIdPresent());
		assertTrue(request.titlePresent());
		assertEquals("A", request.title());
	}

	private IllustrationPatchRequest capturedRequest(IllustrationUpdateService illustrationUpdateService) {
		ArgumentCaptor<IllustrationPatchRequest> requestCaptor =
				ArgumentCaptor.forClass(IllustrationPatchRequest.class);
		verify(illustrationUpdateService).updateBasicMetadata(eq(3L), requestCaptor.capture());
		return requestCaptor.getValue();
	}
}
