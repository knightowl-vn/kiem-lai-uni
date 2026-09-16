package com.universe.wiki.application.saved;

import java.util.Objects;
import java.util.UUID;

/**
 * Lệnh yêu cầu lưu một bài viết Wiki cho người dùng đã xác thực.
 */
public record SaveWikiArticleCommand(
        UUID userId,
        UUID articleId
) {

    public SaveWikiArticleCommand {
        Objects.requireNonNull(userId, "userId không được để trống.");
        Objects.requireNonNull(articleId, "articleId không được để trống.");
    }
}
