package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ChapterSlugAlreadyExistsException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterSlugAlreadyExistsException(
            String message
    ) {
        super(
                "NOVEL_CHAPTER_SLUG_EXISTS",
                message
        );
    }
}