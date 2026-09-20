package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TagService {

	private final TagRepository tagRepository;

	public TagService(TagRepository tagRepository) {
		this.tagRepository = tagRepository;
	}

	public TagSummary create(String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Tag name is required.");
		}

		long tagId = tagRepository.insert(name.trim());
		return tagRepository.findById(tagId)
				.orElseThrow(() -> new IllegalStateException("Created tag could not be found."));
	}

	public List<TagSummary> search(String keyword) {
		return tagRepository.search(keyword);
	}
}
