package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.GalleryAsset;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryFilters;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryQuery;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import com.yuutara.illustrationarchive.dto.IllustrationGalleryPage;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class IllustrationGalleryService {

	private static final int MAX_PAGE_SIZE = 100;

	private final IllustrationRepository illustrationRepository;
	private final AuthorRepository authorRepository;
	private final TagRepository tagRepository;

	public IllustrationGalleryService(IllustrationRepository illustrationRepository,
			AuthorRepository authorRepository, TagRepository tagRepository) {
		this.illustrationRepository = illustrationRepository;
		this.authorRepository = authorRepository;
		this.tagRepository = tagRepository;
	}

	@Transactional(readOnly = true)
	public IllustrationGalleryPage getGallery(int page, int size, IllustrationGalleryQuery query) {
		if (page < 0) {
			throw new IllegalArgumentException("Page must be greater than or equal to 0.");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("Size must be between 1 and 100.");
		}

		long offset = Math.multiplyExact((long) page, (long) size);
		long totalElements = illustrationRepository.count(query);
		int totalPages = calculateTotalPages(totalElements, size);

		List<IllustrationGalleryItem> items = illustrationRepository.findGalleryPage(query, size, offset);
		Map<Long, List<GalleryAsset>> assets = illustrationRepository
				.findGalleryAssetsByIllustrationIds(items.stream().map(IllustrationGalleryItem::id).toList());
		List<IllustrationGalleryItem> previewItems = items.stream().map(item -> new IllustrationGalleryItem(
				item.id(), item.title(), item.author(), item.coverAssetId(), item.coverMimeType(),
				item.assetCount(), item.createdAt(), assets.getOrDefault(item.id(), List.of()))).toList();

		AuthorSummary author = query.authorId() == null ? null : authorRepository.findById(query.authorId())
				.map(value -> new AuthorSummary(value.id(), value.displayName(), value.xUsername())).orElse(null);
		var tag = query.tagId() == null ? null : tagRepository.findById(query.tagId()).orElse(null);
		return new IllustrationGalleryPage(
				page,
				size,
				totalElements,
				totalPages,
				previewItems,
				new IllustrationGalleryFilters(query.q(), query.authorId(), query.tagId(), author, tag)
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
