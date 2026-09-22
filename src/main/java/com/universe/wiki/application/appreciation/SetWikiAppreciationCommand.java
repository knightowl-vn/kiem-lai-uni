package com.universe.wiki.application.appreciation;

import com.universe.wiki.domain.appreciation.WikiAppreciationRating;

import java.util.Objects;
import java.util.UUID;

/**
 * Command cho thao tác thiết lập hoặc cập nhật đánh giá mức độ yêu thích của người dùng đối với một bài viết Wiki.
 *
 * @param wikiArticleId định danh bài viết Wiki
 * @param actorUserId định danh người dùng đã xác thực
 * @param value điểm đánh giá (1 đến 5 sao)
 */
public record SetWikiAppreciationCommand(
        UUID wikiArticleId,
        UUID actorUserId,
        int value
) {
    public SetWikiAppreciationCommand {
        Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");
        Objects.requireNonNull(actorUserId, "ID người dùng không được để trống.");
        if (value < WikiAppreciationRating.MIN_VALUE || value > WikiAppreciationRating.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Giá trị đánh giá phải nằm trong khoảng từ " + WikiAppreciationRating.MIN_VALUE
                            + " đến " + WikiAppreciationRating.MAX_VALUE + " sao, nhận được: " + value
            );
        }
    }
}
