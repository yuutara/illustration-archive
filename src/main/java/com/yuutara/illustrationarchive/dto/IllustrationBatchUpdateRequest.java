package com.yuutara.illustrationarchive.dto;

import java.util.List;

/**
 * One change applied to several works. An absent {@code authorId} leaves authors alone, while an explicit
 * null clears them, the same three-state meaning as {@link IllustrationPatchRequest}. Tags are added and
 * removed rather than replaced, so tags that only some of the works have stay as they are.
 */
public class IllustrationBatchUpdateRequest {

	private List<Long> ids;
	private Long authorId;
	private boolean authorIdPresent;
	private List<Long> addTagIds;
	private List<Long> removeTagIds;

	public void setIds(List<Long> ids) {
		this.ids = ids;
	}

	public void setAuthorId(Long authorId) {
		this.authorId = authorId;
		this.authorIdPresent = true;
	}

	public void setAddTagIds(List<Long> addTagIds) {
		this.addTagIds = addTagIds;
	}

	public void setRemoveTagIds(List<Long> removeTagIds) {
		this.removeTagIds = removeTagIds;
	}

	public List<Long> ids() {
		return ids;
	}

	public Long authorId() {
		return authorId;
	}

	public boolean authorIdPresent() {
		return authorIdPresent;
	}

	public List<Long> addTagIds() {
		return addTagIds;
	}

	public List<Long> removeTagIds() {
		return removeTagIds;
	}
}
