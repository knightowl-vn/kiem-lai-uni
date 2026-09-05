package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterNarrationSegmentNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_CHAPTER_NARRATION_SEGMENT_NOT_FOUND";

    public ChapterNarrationSegmentNotFoundException(UUID segmentId) {
        super(DEFAULT_ERROR_CODE, "Không tìm thấy phân đoạn thuyết minh: " + segmentId);
    }
}
