package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.repository.IllustrationTagRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class IllustrationUpdateService {

	private final IllustrationRepository illustrationRepository;
	private final AuthorRepository authorRepository;
	private final TagRepository tagRepository;
	private final IllustrationTagRepository illustrationTagRepository;

	public IllustrationUpdateService(
			IllustrationRepository illustrationRepository,
			AuthorRepository authorRepository,
			TagRepository tagRepository,
			IllustrationTagRepository illustrationTagRepository
	) {
		this.illustrationRepository = illustrationRepository;
		this.authorRepository = authorRepository;
		this.tagRepository = tagRepository;
		this.illustrationTagRepository = illustrationTagRepository;
	}

	@Transactional
	public void updateBasicMetadata(long id, IllustrationPatchRequest request) {
		illustrationRepository.findDetailBaseById(id)
				.orElseThrow(() -> new IllustrationNotFoundException(id));
		List<Long> tagIds = request.tagIdsPresent() ? validateTagIds(request.tagIds()) : null;

		if (tagIds != null) {
			for (long tagId : tagIds) {
				tagRepository.findById(tagId)
						.orElseThrow(() -> new TagNotFoundException(tagId));
			}
		}
		if (request.authorIdPresent() && request.authorId() != null) {
			authorRepository.findById(request.authorId())
					.orElseThrow(() -> new AuthorNotFoundException(request.authorId()));
		}

		if (!request.titlePresent()
				&& !request.authorIdPresent()
				&& !request.sourceUrlPresent()
				&& !request.notePresent()
				&& !request.tagIdsPresent()) {
			return;
		}

		if (request.titlePresent()
				|| request.authorIdPresent()
				|| request.sourceUrlPresent()
				|| request.notePresent()) {
			illustrationRepository.updateBasicMetadata(id, request);
		}

		if (tagIds != null) {
			illustrationTagRepository.deleteByIllustrationId(id);
			for (long tagId : tagIds) {
				illustrationTagRepository.insert(id, tagId);
			}
		}
	}

	private List<Long> validateTagIds(List<Long> tagIds) {
		if (tagIds == null) {
			throw new IllegalArgumentException("tagIds must not be null. Use [] to clear tags.");
		}

		LinkedHashSet<Long> uniqueTagIds = new LinkedHashSet<>();
		for (Long tagId : tagIds) {
			if (tagId == null || tagId <= 0) {
				throw new IllegalArgumentException("tagIds must contain only positive, non-null IDs.");
			}
			uniqueTagIds.add(tagId);
		}
		return new ArrayList<>(uniqueTagIds);
	}
}
