package com.yuutara.illustrationarchive.repository;

import com.yuutara.illustrationarchive.dto.IllustrationGalleryItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis SQL for the gallery list read; the statements live in IllustrationGalleryMapper.xml next to
 * this interface on the classpath. Only {@link IllustrationRepository} calls it, so the repository keeps
 * the public contract and the Java-side rules (LIKE escaping, grouping assets per work).
 *
 * <p>Filters arrive as plain parameters rather than the query record so the dynamic SQL tests only
 * lists and strings. An empty id list means "no filter on this group"; {@code pattern} is a ready LIKE
 * pattern escaped with {@code !}, or null for no keyword.
 */
@Mapper
public interface IllustrationGalleryMapper {

	long count(@Param("authorIds") List<Long> authorIds, @Param("tagIds") List<Long> tagIds,
			@Param("pattern") String pattern);

	/** Rows carry no page assets yet; the service attaches them from {@link #findAssets}. */
	List<IllustrationGalleryItem> findPage(@Param("authorIds") List<Long> authorIds, @Param("tagIds") List<Long> tagIds,
			@Param("pattern") String pattern, @Param("size") int size, @Param("offset") long offset);

	/** Assets of the given (non-empty) works, ordered by work, then sort order and id. */
	List<AssetRow> findAssets(@Param("illustrationIds") List<Long> illustrationIds);

	record AssetRow(Long illustrationId, Long id, String mimeType, int sortOrder) {
	}
}
