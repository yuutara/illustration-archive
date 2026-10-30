package com.yuutara.illustrationarchive.dto;

/** A tag found on the requested works and how many of those works carry it. */
public record IllustrationBatchTagCount(long id, String name, long count) {
}
