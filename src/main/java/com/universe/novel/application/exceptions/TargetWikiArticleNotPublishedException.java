package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class TargetWikiArticleNotPublishedException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public TargetWikiArticleNotPublishedException(UUID wikiArticleId) {
        super(
                "TARGET_WIKI_ARTICLE_NOT_PUBLISHED",
                "Bài viết Wiki đích không tồn tại hoặc chưa được xuất bản: " + wikiArticleId
        );
    }
}
