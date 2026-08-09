package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class WikiArticleNotFoundException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public WikiArticleNotFoundException(
            UUID articleId
    ) {
        super(
                "WIKI_ARTICLE_NOT_FOUND",
                "Không tìm thấy bài viết Wiki: "
                        + articleId
        );
    }
}