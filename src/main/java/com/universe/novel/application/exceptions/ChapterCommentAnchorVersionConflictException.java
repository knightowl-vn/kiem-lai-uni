package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Exception thrown when a client attempts to anchor a new comment against a
 * chapter contentVersion that does not match the current canonical Reader snapshot version.
 */
public class ChapterCommentAnchorVersionConflictException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public ChapterCommentAnchorVersionConflictException(UUID chapterId, long requestedVersion, long currentVersion) {
        super(
                "NOVEL_CHAPTER_COMMENT_ANCHOR_VERSION_CONFLICT",
                "Nội dung chương đã thay đổi. Phiên bản yêu cầu: " + requestedVersion + ", phiên bản hiện tại: " + currentVersion
        );
    }
}
