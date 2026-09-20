package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.springframework.stereotype.Service;

@Service
public class IllustrationUpdateService {

	private final IllustrationRepository illustrationRepository;
	private final AuthorRepository authorRepository;

	public IllustrationUpdateService(
			IllustrationRepository illustrationRepository,
			AuthorRepository authorRepository
	) {
		this.illustrationRepository = illustrationRepository;
		this.authorRepository = authorRepository;
	}

	public void updateBasicMetadata(long id, IllustrationPatchRequest request) {
		illustrationRepository.findDetailBaseById(id)
				.orElseThrow(() -> new IllustrationNotFoundException(id));

		if (request.authorIdPresent() && request.authorId() != null) {
			authorRepository.findById(request.authorId())
					.orElseThrow(() -> new AuthorNotFoundException(request.authorId()));
		}

		if (!request.titlePresent()
				&& !request.authorIdPresent()
				&& !request.sourceUrlPresent()
				&& !request.notePresent()) {
			return;
		}

		illustrationRepository.updateBasicMetadata(id, request);
	}
}
