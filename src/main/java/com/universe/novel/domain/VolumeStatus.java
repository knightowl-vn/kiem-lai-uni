package com.universe.novel.domain;

/**
 * Vòng đời xuất bản của một tập (volume).
 */
public enum VolumeStatus {

    /**
     * Tập đang được soạn thảo.
     */
    DRAFT,

    /**
     * Tập đã được công khai.
     */
    PUBLISHED,

    /**
     * Tập không còn được hiển thị công khai.
     */
    ARCHIVED
}
