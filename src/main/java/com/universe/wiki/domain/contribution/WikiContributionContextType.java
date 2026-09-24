package com.universe.wiki.domain.contribution;

/**
 * Ngữ cảnh áp dụng của đóng góp nội dung Wiki.
 *
 * - GENERAL: Đóng góp cho toàn bộ bài viết (không gắn với đoạn văn bản cụ thể);
 * - TEXT_SELECTION: Đóng góp gắn với một đoạn văn bản được bôi đen/chọn trên bài viết.
 */
public enum WikiContributionContextType {
    GENERAL,
    TEXT_SELECTION
}
