package com.universe.wiki.entry.dto.appreciation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

/**
 * Payload yêu cầu thiết lập mức độ yêu thích cho bài viết Wiki.
 *
 * <p>Chứa duy nhất trường value. Định danh người dùng (actor) được trích xuất an toàn
 * từ session máy chủ (AuthenticatedRequestIdentityAccessor).
 *
 * @param value điểm đánh giá dạng số thực (1.0..5.0, bước 0.5)
 */
public record SetWikiAppreciationRequest(
        @JsonDeserialize(using = StrictHalfStarScoreDeserializer.class)
        BigDecimal value
) {

    @JsonCreator
    public SetWikiAppreciationRequest(@JsonProperty("value") BigDecimal value) {
        this.value = value;
    }
}
