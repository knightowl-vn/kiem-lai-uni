package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Được ném khi một bài viết khác đã sử dụng slug.
 */
public class ArticleSlugAlreadyExistsException
        extends BaseApplicationException {

    private static final long serialVersionUID =
            1L;

    public ArticleSlugAlreadyExistsException(
            String message
    ) {
        super(
                "WIKI_ARTICLE_SLUG_EXISTS",
                message
        );
    }
}