package com.universe.novel.application.chapter;

import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;

final class ChapterSlugGenerator {

    private ChapterSlugGenerator() {
    }

    static Slug generate(
            Volume volume,
            int chapterNumber
    ) {
        return new Slug(
                "quyen-"
                        + volume.getSortOrder()
                        + "-chuong-"
                        + chapterNumber
        );
    }
}
