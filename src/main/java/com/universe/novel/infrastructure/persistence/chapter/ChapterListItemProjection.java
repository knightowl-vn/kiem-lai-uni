package com.universe.novel.infrastructure.persistence.chapter;

import java.time.Instant;

public interface ChapterListItemProjection {

    String getId();

    int getChapterNumber();

    String getTitle();

    String getSlug();

    String getStatus();

    Instant getUpdatedAt();
}