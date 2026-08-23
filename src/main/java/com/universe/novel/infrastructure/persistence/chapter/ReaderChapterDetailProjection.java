package com.universe.novel.infrastructure.persistence.chapter;

public interface ReaderChapterDetailProjection {

    String getId();

    String getVolumeId();

    Integer getChapterNumber();

    String getTitle();

    String getSlug();

    String getContent();

    String getVolumeTitle();

    String getVolumeSlug();

    Integer getVolumeSortOrder();
}
