package com.universe.novel.infrastructure.persistence.chapter;

/**
 * Spring Data projection interface for lightweight chapter locator queries.
 */
public interface PublishedChapterLocatorProjection {

    String getId();

    int getChapterNumber();

    String getTitle();

    String getSlug();

    int getVolumeSortOrder();

    String getVolumeTitle();
}
