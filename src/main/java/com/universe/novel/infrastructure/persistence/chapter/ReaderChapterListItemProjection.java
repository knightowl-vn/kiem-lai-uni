package com.universe.novel.infrastructure.persistence.chapter;

public interface ReaderChapterListItemProjection {

    String getId();

    Integer getChapterNumber();

    String getTitle();

    String getSlug();
}