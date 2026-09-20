package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthorService {

	private final AuthorRepository authorRepository;

	public AuthorService(AuthorRepository authorRepository) {
		this.authorRepository = authorRepository;
	}

	public AuthorDetail create(String displayName, String xUsername) {
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("Display name is required.");
		}

		String normalizedDisplayName = displayName.trim();
		String normalizedXUsername = normalizeXUsername(xUsername);
		long authorId = authorRepository.insert(normalizedDisplayName, normalizedXUsername);

		return authorRepository.findById(authorId)
				.orElseThrow(() -> new IllegalStateException("Created author could not be found."));
	}

	public List<AuthorSummary> search(String keyword) {
		return authorRepository.search(keyword);
	}

	private String normalizeXUsername(String xUsername) {
		if (xUsername == null) {
			return null;
		}

		String normalized = xUsername.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
