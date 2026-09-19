package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationPatchRequest;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.springframework.stereotype.Service;

@Service
public class IllustrationUpdateService {

	private final IllustrationRepository illustrationRepository;

	public IllustrationUpdateService(IllustrationRepository illustrationRepository) {
		this.illustrationRepository = illustrationRepository;
	}

	public void updateBasicMetadata(long id, IllustrationPatchRequest request) {
		illustrationRepository.findDetailBaseById(id)
				.orElseThrow(() -> new IllustrationNotFoundException(id));

		if (!request.titlePresent() && !request.sourceUrlPresent() && !request.notePresent()) {
			return;
		}

		illustrationRepository.updateBasicMetadata(id, request);
	}
}
