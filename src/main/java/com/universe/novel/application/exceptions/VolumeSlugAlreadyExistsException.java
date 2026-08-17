package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class VolumeSlugAlreadyExistsException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public VolumeSlugAlreadyExistsException(
            String message
    ) {
        super(
                "NOVEL_VOLUME_SLUG_EXISTS",
                message
        );
    }
}