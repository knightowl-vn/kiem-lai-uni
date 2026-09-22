package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ nghiệp vụ khi bài viết Wiki được yêu cầu đánh giá không tồn tại
 * hoặc không đủ điều kiện (DRAFT, ARCHIVED, hoặc articleType không phải CHARACTER/FACTION).
 */
public class WikiAppreciationTargetNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiAppreciationTargetNotFoundException(UUID articleId) {
        super(
                "WIKI_APPRECIATION_TARGET_NOT_FOUND",
                "Không tìm thấy đối tượng bài viết Wiki hợp lệ cho đánh giá mức độ yêu thích với ID: " + articleId
        );
    }
}
