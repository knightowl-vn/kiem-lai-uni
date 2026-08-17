package com.universe.novel.domain;

/**
 * Vòng đời xuất bản của một chương.
 */
public enum ChapterStatus {

    /**
     * Chương đang được soạn thảo.
     */
    DRAFT,

    /**
     * Chương đã được công khai.
     */
    PUBLISHED,

    /**
     * Chương không còn được hiển thị công khai.
     */
    ARCHIVED
}
