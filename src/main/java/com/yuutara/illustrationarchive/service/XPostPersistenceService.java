package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import com.yuutara.illustrationarchive.repository.AssetRepository;
import com.yuutara.illustrationarchive.repository.AuthorRepository;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import com.yuutara.illustrationarchive.repository.XLikeRepository;
import com.yuutara.illustrationarchive.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class XPostPersistenceService {
	private final XLikeRepository items;
	private final AuthorRepository authors;
	private final IllustrationRepository illustrations;
	private final AssetRepository assets;

	public XPostPersistenceService(XLikeRepository items, AuthorRepository authors,
			IllustrationRepository illustrations, AssetRepository assets) {
		this.items = items;
		this.authors = authors;
		this.illustrations = illustrations;
		this.assets = assets;
	}

	/** One database transaction covers the author, illustration, every asset, and Inbox state. */
	@Transactional
	public long persist(XLikeRepository.ImportItem candidate, List<XLikeMedia> media,
			List<StoredFile> files) {
		XLikeRepository.ImportItem current = items.findForImport(candidate.id(), true)
				.orElseThrow(() -> new IllegalStateException("X Like item no longer exists."));
		if (current.status() != XLikeStatus.PENDING) {
			throw new IllegalStateException("X Like item is no longer pending.");
		}
		if (media.size() != files.size() || media.isEmpty()) {
			throw new IllegalArgumentException("X Post must have all photos before persistence.");
		}
		Set<String> hashes = new HashSet<>();
		for (StoredFile file : files) {
			if (!hashes.add(file.sha256())) {
				throw new XPostDuplicateException("Two photos in this X Post have identical content.");
			}
			if (assets.findIllustrationIdBySha256(file.sha256()).isPresent()) {
				throw new XPostDuplicateException("An X Post photo already exists in the archive.");
			}
		}

		var existingAuthorId = authors.findIdByXUserId(current.xAuthorId());
        long authorId;
        if (existingAuthorId.isPresent()) authorId = existingAuthorId.get();
        else {
            try {
                authorId = authors.claimLegacyXAuthor(current.xAuthorId(), current.authorDisplayName(), current.authorUsername())
                        .orElseGet(() -> authors.insertXAuthor(current.xAuthorId(), current.authorDisplayName(), current.authorUsername()));
            } catch (DuplicateKeyException collision) {
                // The unique stable ID resolves an import racing with another Post.
                authorId = authors.findIdByXUserId(current.xAuthorId()).orElseThrow(() -> collision);
            }
        }
		if (existingAuthorId.isPresent()) {
			authors.updateXAuthor(authorId, current.authorDisplayName(), current.authorUsername());
		}
		long illustrationId = illustrations.insertXPost(authorId,
				"https://x.com/" + current.authorUsername() + "/status/" + current.xPostId());
		for (int index = 0; index < files.size(); index++) {
			StoredFile file = files.get(index);
			assets.insert(illustrationId, file.originalFilename(), file.storageKey(),
					file.mimeType(), file.fileSize(), media.get(index).sortOrder(), file.sha256());
		}
		items.markImported(current.id(), illustrationId);
		return illustrationId;
	}
}
