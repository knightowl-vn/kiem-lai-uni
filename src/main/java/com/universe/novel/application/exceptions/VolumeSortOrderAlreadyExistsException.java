package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class VolumeSortOrderAlreadyExistsException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public VolumeSortOrderAlreadyExistsException(
            String message
    ) {
        super(
                "NOVEL_VOLUME_SORT_ORDER_EXISTS",
                message
        );
    }
}