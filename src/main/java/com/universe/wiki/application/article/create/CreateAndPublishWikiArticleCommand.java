package com.universe.wiki.application.article.create;

import com.universe.wiki.domain.article.ArticleType;

import java.util.UUID;

/**
 * Yêu cầu tạo một bài Wiki hoàn chỉnh
 * và xuất bản ngay trong lần tạo đầu tiên.
 */
public record CreateAndPublishWikiArticleCommand(
        String title,
        ArticleType articleType,
        String summary,
        String content,
        String editSummary,
        UUID actorId
) {
}