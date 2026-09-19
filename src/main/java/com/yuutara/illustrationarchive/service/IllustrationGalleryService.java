package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.springframework.stereotype.Service;

@Service
public class IllustrationGalleryService {

	private static final int MAX_PAGE_SIZE = 100;

	private final IllustrationRepository illustrationRepository;

	public IllustrationGalleryService(IllustrationRepository illustrationRepository) {
		this.illustrationRepository = illustrationRepository;
	}

	public IllustrationGalleryPage getGallery(int page, int size) {
		if (page < 0) {
			throw new IllegalArgumentException("Page must be greater than or equal to 0.");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("Size must be between 1 and 100.");
		}

		long offset = Math.multiplyExact((long) page, (long) size);
		long totalElements = illustrationRepository.count();
		int totalPages = calculateTotalPages(totalElements, size);

		return new IllustrationGalleryPage(
				page,
				size,
				totalElements,
				totalPages,
				illustrationRepository.findGalleryPage(size, offset)
		);
	}

	private int calculateTotalPages(long totalElements, int size) {
		if (totalElements == 0) {
			return 0;
		}

		long totalPages = ((totalElements - 1) / size) + 1;
		return Math.toIntExact(totalPages);
	}
}
