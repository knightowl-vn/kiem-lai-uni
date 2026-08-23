package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class VolumeNotPublishedException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public VolumeNotPublishedException(
            UUID volumeId
    ) {
        super(
                "NOVEL_VOLUME_NOT_PUBLISHED",
                "Không thể xuất bản chương vì tập cha chưa được xuất bản: "
                        + volumeId
        );
    }
}