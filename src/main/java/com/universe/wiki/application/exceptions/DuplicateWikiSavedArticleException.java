package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi cố gắng lưu một bài viết Wiki đã tồn tại trong danh sách lưu của người dùng.
 */
public class DuplicateWikiSavedArticleException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public DuplicateWikiSavedArticleException(UUID userId, UUID articleId, Throwable cause) {
        super(
                "WIKI_SAVED_ARTICLE_DUPLICATE",
                "Bài viết Wiki " + articleId + " đã được người dùng " + userId + " lưu trước đó.",
                cause
        );
    }
}
