package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class VolumeNotFoundException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public VolumeNotFoundException(
            UUID volumeId
    ) {
        super(
                "NOVEL_VOLUME_NOT_FOUND",
                "Không tìm thấy tập: "
                        + volumeId
        );
    }
}