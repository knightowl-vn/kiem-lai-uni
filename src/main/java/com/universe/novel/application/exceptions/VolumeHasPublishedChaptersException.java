package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class VolumeHasPublishedChaptersException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public VolumeHasPublishedChaptersException(
            UUID volumeId
    ) {
        super(
                "NOVEL_VOLUME_HAS_PUBLISHED_CHAPTERS",
                "Không thể lưu trữ tập vì vẫn còn chương đã xuất bản: "
                        + volumeId
        );
    }
}