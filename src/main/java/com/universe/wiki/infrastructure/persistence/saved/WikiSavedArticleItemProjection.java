package com.universe.wiki.infrastructure.persistence.saved;

import java.time.Instant;

/**
 * Spring Data Projection interface cho truy vấn danh sách bài viết Wiki đã lưu.
 *
 * Chỉ chiếu các trường cần thiết phục vụ danh sách, hoàn toàn không tải nội dung lớn (content markdown).
 */
public interface WikiSavedArticleItemProjection {

    String getSavedId();

    String getArticleId();

    Instant getSavedAt();

    String getArticleStatus();

    String getTitle();

    String getSlug();

    String getArticleType();

    String getSummary();
}
