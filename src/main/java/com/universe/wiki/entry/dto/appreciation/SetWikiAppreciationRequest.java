package com.universe.wiki.entry.dto.appreciation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Payload yêu cầu thiết lập mức độ yêu thích cho bài viết Wiki.
 *
 * <p>Chứa duy nhất trường value. Định danh người dùng (actor) được trích xuất an toàn
 * từ session máy chủ (AuthenticatedRequestIdentityAccessor).
 *
 * @param value điểm đánh giá (1..5)
 */
public record SetWikiAppreciationRequest(
        Integer value
) {

    @JsonCreator
    public static SetWikiAppreciationRequest create(@JsonProperty("value") JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return new SetWikiAppreciationRequest(null);
        }
        if (!valueNode.isInt()) {
            throw new IllegalArgumentException("Mức độ yêu thích phải là số nguyên từ 1 đến 5.");
        }
        return new SetWikiAppreciationRequest(valueNode.intValue());
    }
}

