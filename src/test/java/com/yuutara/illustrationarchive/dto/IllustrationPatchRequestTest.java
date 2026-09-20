package com.yuutara.illustrationarchive.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IllustrationPatchRequestTest {

	@Test
	void startsWithAllFieldsAbsent() {
		IllustrationPatchRequest request = new IllustrationPatchRequest();

		assertFalse(request.titlePresent());
		assertFalse(request.sourceUrlPresent());
		assertFalse(request.notePresent());
		assertFalse(request.authorIdPresent());
	}

	@Test
	void marksTitleAsPresentForStringAndNullValues() {
		IllustrationPatchRequest withValue = new IllustrationPatchRequest();
		withValue.setTitle("A");
		IllustrationPatchRequest withNull = new IllustrationPatchRequest();
		withNull.setTitle(null);

		assertTrue(withValue.titlePresent());
		assertEquals("A", withValue.title());
		assertTrue(withNull.titlePresent());
		assertNull(withNull.title());
	}

	@Test
	void marksSourceUrlAndNoteAsPresentForStringAndNullValues() {
		IllustrationPatchRequest request = new IllustrationPatchRequest();
		request.setSourceUrl(null);
		request.setNote("Updated note");

		assertTrue(request.sourceUrlPresent());
		assertNull(request.sourceUrl());
		assertTrue(request.notePresent());
		assertEquals("Updated note", request.note());
	}

	@Test
	void marksAuthorIdAsPresentForValueAndNull() {
		IllustrationPatchRequest withValue = new IllustrationPatchRequest();
		withValue.setAuthorId(1L);
		IllustrationPatchRequest withNull = new IllustrationPatchRequest();
		withNull.setAuthorId(null);

		assertTrue(withValue.authorIdPresent());
		assertEquals(1L, withValue.authorId());
		assertTrue(withNull.authorIdPresent());
		assertNull(withNull.authorId());
	}
}
