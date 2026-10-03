package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuthorService {

	private final AuthorRepository authorRepository;

	public AuthorService(AuthorRepository authorRepository) {
		this.authorRepository = authorRepository;
	}

    @Transactional
	public AuthorDetail create(String displayName, String xUsername) {
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("Display name is required.");
		}

		String normalizedDisplayName = displayName.trim();
		String normalizedXUsername = normalizeXUsername(xUsername);
        var matchingIds = normalizedXUsername == null ? List.<Long>of() : authorRepository.findIdsByNormalizedXUsername(normalizedXUsername);
        if (matchingIds.size() > 1) throw new IllegalArgumentException("This X handle has multiple authors. Select an existing author instead.");
		long authorId = matchingIds.isEmpty() ? authorRepository.insert(normalizedDisplayName, normalizedXUsername) : matchingIds.get(0);

		return authorRepository.findById(authorId)
				.orElseThrow(() -> new IllegalStateException("Created author could not be found."));
	}

	public List<AuthorSummary> search(String keyword, int limit, int offset) {
		if (limit < 1 || limit > 100 || offset < 0) {
			throw new IllegalArgumentException("Limit must be between 1 and 100; offset must be nonnegative.");
		}
		return authorRepository.search(keyword, limit, offset);
	}

	private String normalizeXUsername(String xUsername) {
		if (xUsername == null) {
			return null;
		}

		String normalized = xUsername.trim().replaceFirst("^@", "");
		return normalized.isEmpty() ? null : normalized;
	}
}
