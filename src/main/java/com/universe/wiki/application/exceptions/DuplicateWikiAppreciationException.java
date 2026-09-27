package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi xảy ra xung đột đồng thời (duplicate race) tạo mới đánh giá bài viết Wiki.
 */
public class DuplicateWikiAppreciationException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public DuplicateWikiAppreciationException(UUID wikiArticleId, UUID actorUserId, Throwable cause) {
        super(
                "WIKI_APPRECIATION_DUPLICATE_RACE",
                "Đã tồn tại bản ghi đánh giá cho bài viết Wiki " + wikiArticleId + " và người dùng " + actorUserId + ".",
                cause
        );
    }
}
