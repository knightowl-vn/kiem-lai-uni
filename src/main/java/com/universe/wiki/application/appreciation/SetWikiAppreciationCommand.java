package com.universe.wiki.application.appreciation;

import com.universe.wiki.domain.appreciation.WikiAppreciationScore;

import java.util.Objects;
import java.util.UUID;

/**
 * Command cho thao tác thiết lập hoặc cập nhật đánh giá mức độ yêu thích của người dùng đối với một bài viết Wiki.
 *
 * @param wikiArticleId định danh bài viết Wiki
 * @param actorUserId định danh người dùng đã xác thực
 * @param score đối tượng điểm đánh giá (WikiAppreciationScore)
 */
public record SetWikiAppreciationCommand(
        UUID wikiArticleId,
        UUID actorUserId,
        WikiAppreciationScore score
) {
    public SetWikiAppreciationCommand {
        Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");
        Objects.requireNonNull(actorUserId, "ID người dùng không được để trống.");
        Objects.requireNonNull(score, "WikiAppreciationScore không được để trống.");
    }
}
