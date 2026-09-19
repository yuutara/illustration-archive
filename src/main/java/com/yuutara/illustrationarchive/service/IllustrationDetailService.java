package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.IllustrationDetail;
import com.yuutara.illustrationarchive.repository.IllustrationDetailBase;
import com.yuutara.illustrationarchive.repository.IllustrationRepository;
import org.springframework.stereotype.Service;

@Service
public class IllustrationDetailService {

	private final IllustrationRepository illustrationRepository;

	public IllustrationDetailService(IllustrationRepository illustrationRepository) {
		this.illustrationRepository = illustrationRepository;
	}

	public IllustrationDetail getDetail(long id) {
		IllustrationDetailBase base = illustrationRepository.findDetailBaseById(id)
				.orElseThrow(() -> new IllustrationNotFoundException(id));

		return new IllustrationDetail(
				base.id(),
				base.title(),
				base.author(),
				base.sourceUrl(),
				base.note(),
				illustrationRepository.findAssetSummariesByIllustrationId(id),
				illustrationRepository.findTagSummariesByIllustrationId(id),
				base.createdAt(),
				base.updatedAt()
		);
	}
}
