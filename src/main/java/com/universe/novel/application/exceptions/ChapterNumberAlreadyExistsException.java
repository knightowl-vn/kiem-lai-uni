package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ChapterNumberAlreadyExistsException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterNumberAlreadyExistsException(
            int chapterNumber
    ) {
        super(
                "NOVEL_CHAPTER_NUMBER_EXISTS",
                "Số chương đã tồn tại: "
                        + chapterNumber
        );
    }
}
