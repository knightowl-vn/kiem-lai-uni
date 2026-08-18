package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterNotFoundException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterNotFoundException(
            UUID chapterId
    ) {
        super(
                "NOVEL_CHAPTER_NOT_FOUND",
                "Không tìm thấy chương: "
                        + chapterId
        );
    }
}