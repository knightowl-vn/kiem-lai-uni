package com.universe.interaction.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when an author attempts to delete a comment that has one or more replies or descendants.
 */
public class CommentHasRepliesException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommentHasRepliesException(UUID commentId) {
        super(
                "COMMENT_HAS_REPLIES",
                "Không thể xóa bình luận đang có phản hồi."
        );
    }

    public CommentHasRepliesException(String message) {
        super("COMMENT_HAS_REPLIES", message);
    }
}
