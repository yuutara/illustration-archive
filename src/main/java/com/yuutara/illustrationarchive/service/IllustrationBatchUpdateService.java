package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationBatchTagCount;
import com.yuutara.illustrationarchive.dto.IllustrationBatchUpdateRequest;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationBatchRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies one metadata change to several works in a single transaction: either every selected work
 * changes or none does, so a failure never leaves the selection half organized.
 */
@Service
public class IllustrationBatchUpdateService {

	static final int MAX_WORKS = 500;
	private static final int MAX_TAGS = 100;

	private final IllustrationBatchRepository batchRepository;
	private final AuthorRepository authorRepository;
	private final TagRepository tagRepository;

	public IllustrationBatchUpdateService(IllustrationBatchRepository batchRepository,
			AuthorRepository authorRepository, TagRepository tagRepository) {
		this.batchRepository = batchRepository;
		this.authorRepository = authorRepository;
		this.tagRepository = tagRepository;
	}

	@Transactional
	public void update(IllustrationBatchUpdateRequest request) {
		List<Long> ids = normalizeWorks(request.ids());
		List<Long> addTagIds = normalizeTags(request.addTagIds());
		List<Long> removeTagIds = normalizeTags(request.removeTagIds());
		if (!request.authorIdPresent() && addTagIds.isEmpty() && removeTagIds.isEmpty()) {
			throw new IllegalArgumentException("A batch update must change the author or the tags.");
		}
		if (addTagIds.stream().anyMatch(removeTagIds::contains)) {
			throw new IllegalArgumentException("A tag cannot be added and removed at the same time.");
		}

		requireWorks(ids);
		if (request.authorId() != null) {
			authorRepository.findById(request.authorId())
					.orElseThrow(() -> new AuthorNotFoundException(request.authorId()));
		}
		requireTags(addTagIds);

		if (request.authorIdPresent()) {
			batchRepository.updateAuthor(ids, request.authorId());
		}
		if (!removeTagIds.isEmpty()) {
			batchRepository.removeTags(ids, removeTagIds);
		}
		if (!addTagIds.isEmpty()) {
			batchRepository.addTags(ids, addTagIds);
		}
	}

	@Transactional(readOnly = true)
	public List<IllustrationBatchTagCount> countTags(List<Long> rawIds) {
		return batchRepository.countTags(normalizeWorks(rawIds));
	}

	private void requireWorks(List<Long> ids) {
		Set<Long> existing = new HashSet<>(batchRepository.findExistingIds(ids));
		for (long id : ids) {
			if (!existing.contains(id)) {
				throw new IllustrationNotFoundException(id);
			}
		}
	}

	private void requireTags(List<Long> tagIds) {
		if (tagIds.isEmpty()) {
			return;
		}
		Set<Long> existing = new HashSet<>();
		tagRepository.findSummariesByIds(tagIds).forEach(tag -> existing.add(tag.id()));
		for (long id : tagIds) {
			if (!existing.contains(id)) {
				throw new TagNotFoundException(id);
			}
		}
	}

	private static List<Long> normalizeWorks(List<Long> ids) {
		if (ids == null || ids.isEmpty() || ids.size() > MAX_WORKS) {
			throw new IllegalArgumentException("Select between 1 and " + MAX_WORKS + " works.");
		}
		return positiveDistinct(ids);
	}

	private static List<Long> normalizeTags(List<Long> ids) {
		if (ids == null) {
			return List.of();
		}
		if (ids.size() > MAX_TAGS) {
			throw new IllegalArgumentException("At most " + MAX_TAGS + " tags can change at once.");
		}
		return positiveDistinct(ids);
	}

	private static List<Long> positiveDistinct(List<Long> ids) {
		if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
			throw new IllegalArgumentException("Ids must be positive.");
		}
		return ids.stream().distinct().sorted().toList();
	}
}
