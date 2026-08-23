package com.universe.novel.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NovelLifecycleStatusTest {

    @Test
    @DisplayName(
            "VolumeStatus chứa đúng thứ tự DRAFT, PUBLISHED, ARCHIVED"
    )
    void shouldContainVolumeStatusValuesInDeclaredOrder() {
        assertThat(VolumeStatus.values())
                .containsExactly(
                        VolumeStatus.DRAFT,
                        VolumeStatus.PUBLISHED,
                        VolumeStatus.ARCHIVED
                );
    }

    @Test
    @DisplayName(
            "ChapterStatus chứa đúng thứ tự DRAFT, PUBLISHED, ARCHIVED"
    )
    void shouldContainChapterStatusValuesInDeclaredOrder() {
        assertThat(ChapterStatus.values())
                .containsExactly(
                        ChapterStatus.DRAFT,
                        ChapterStatus.PUBLISHED,
                        ChapterStatus.ARCHIVED
                );
    }

    @Test
    @DisplayName(
            "NovelStatus chứa đúng các giá trị ONGOING, COMPLETED, HIATUS"
    )
    void shouldContainNovelStatusValuesInDeclaredOrder() {
        assertThat(NovelStatus.values())
                .containsExactly(
                        NovelStatus.ONGOING,
                        NovelStatus.COMPLETED,
                        NovelStatus.HIATUS
                );
    }
}
