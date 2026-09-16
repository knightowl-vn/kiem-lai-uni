package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ChapterCommentAnchorNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public ChapterCommentAnchorNotFoundException(UUID rootCommentId) {
        super(
                "NOVEL_CHAPTER_COMMENT_ANCHOR_NOT_FOUND",
                "Không tìm thấy anchor cho bình luận gốc: " + rootCommentId
        );
    }
}
