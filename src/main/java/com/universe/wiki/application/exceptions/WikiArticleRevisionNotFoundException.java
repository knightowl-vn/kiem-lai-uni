package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class WikiArticleRevisionNotFoundException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public WikiArticleRevisionNotFoundException(
            UUID articleId,
            long revisionNumber
    ) {
        super(
                "WIKI_ARTICLE_REVISION_NOT_FOUND",
                "Không tìm thấy revision "
                        + revisionNumber
                        + " của bài viết Wiki: "
                        + articleId
        );
    }
}