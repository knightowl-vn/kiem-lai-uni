package com.universe.novel.infrastructure.persistence.volume;

public interface ReaderVolumeListItemProjection {

    String getId();

    String getTitle();

    String getSlug();

    Integer getSortOrder();

    Long getPublishedChapterCount();
}