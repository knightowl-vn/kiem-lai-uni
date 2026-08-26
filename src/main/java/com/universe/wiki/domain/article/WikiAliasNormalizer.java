package com.universe.wiki.domain.article;

import java.util.Locale;

/**
 * Utility chuẩn hóa alias cho bài viết Wiki.
 *
 * Quy tắc:
 * - Null hoặc chuỗi rỗng -> trả về chuỗi rỗng ""
 * - Trim khoảng trắng đầu/cuối
 * - Gộp các khoảng trắng liên tiếp thành 1 dấu cách duy nhất
 * - Chuyển về chữ thường (lowercase) theo Locale.ROOT cho normalizedAlias
 */
public final class WikiAliasNormalizer {

    private WikiAliasNormalizer() {
    }

    /**
     * Chuẩn hóa giá trị hiển thị của alias (giữ nguyên hoa/thường nhưng trim và collapse space).
     */
    public static String cleanDisplayAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return "";
        }
        return alias.trim().replaceAll("\\s+", " ");
    }

    /**
     * Chuẩn hóa alias phục vụ indexing và tìm kiếm chính xác (lowercase + trim + collapse space).
     */
    public static String normalize(String alias) {
        String cleaned = cleanDisplayAlias(alias);
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }
}