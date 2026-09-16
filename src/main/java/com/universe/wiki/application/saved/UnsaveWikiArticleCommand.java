package com.universe.wiki.application.saved;

import java.util.Objects;
import java.util.UUID;

/**
 * Lệnh yêu cầu bỏ lưu một bài viết Wiki của người dùng đã xác thực.
 */
public record UnsaveWikiArticleCommand(
        UUID userId,
        UUID articleId
) {

    public UnsaveWikiArticleCommand {
        Objects.requireNonNull(userId, "userId không được để trống.");
        Objects.requireNonNull(articleId, "articleId không được để trống.");
    }
}
