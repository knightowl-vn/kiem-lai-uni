package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ChapterNarrationSegmentInvalidStateException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_CHAPTER_NARRATION_SEGMENT_INVALID_STATE";

    public ChapterNarrationSegmentInvalidStateException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }
}
