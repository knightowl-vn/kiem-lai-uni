package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterRevisionNotFoundException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ChapterRevisionNotFoundException(
            UUID chapterId,
            long revisionNumber
    ) {
        super(
                "NOVEL_CHAPTER_REVISION_NOT_FOUND",
                "Không tìm thấy phiên bản "
                        + revisionNumber
                        + " của chương: "
                        + chapterId
        );
    }
}
