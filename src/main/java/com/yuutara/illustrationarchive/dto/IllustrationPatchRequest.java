package com.yuutara.illustrationarchive.dto;

public class IllustrationPatchRequest {

	private String title;
	private boolean titlePresent;
	private String sourceUrl;
	private boolean sourceUrlPresent;
	private String note;
	private boolean notePresent;
	private Long authorId;
	private boolean authorIdPresent;

	public void setTitle(String title) {
		this.title = title;
		this.titlePresent = true;
	}

	public void setSourceUrl(String sourceUrl) {
		this.sourceUrl = sourceUrl;
		this.sourceUrlPresent = true;
	}

	public void setNote(String note) {
		this.note = note;
		this.notePresent = true;
	}

	public void setAuthorId(Long authorId) {
		this.authorId = authorId;
		this.authorIdPresent = true;
	}

	public String title() {
		return title;
	}

	public boolean titlePresent() {
		return titlePresent;
	}

	public String sourceUrl() {
		return sourceUrl;
	}

	public boolean sourceUrlPresent() {
		return sourceUrlPresent;
	}

	public String note() {
		return note;
	}

	public boolean notePresent() {
		return notePresent;
	}

	public Long authorId() {
		return authorId;
	}

	public boolean authorIdPresent() {
		return authorIdPresent;
	}
}
