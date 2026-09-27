package com.universe.interaction.application.exceptions;

import com.universe.interaction.domain.CommentTarget;
import com.universe.shared.exceptions.BaseApplicationException;

public class CommentTargetNotEligibleException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public CommentTargetNotEligibleException(CommentTarget target) {
        super("COMMENT_TARGET_NOT_ELIGIBLE", "Comment target is not eligible for comments: " + target);
    }

    public CommentTargetNotEligibleException(String message) {
        super("COMMENT_TARGET_NOT_ELIGIBLE", message);
    }
}
