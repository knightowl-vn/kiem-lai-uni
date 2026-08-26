package com.universe.wiki.domain.article;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Utility chuẩn hóa alias cho bài viết Wiki.
 *
 * Quy tắc:
 * - Null hoặc chuỗi rỗng -> trả về chuỗi rỗng ""
 * - Trim khoảng trắng đầu/cuối
 * - Gộp các khoảng trắng liên tiếp thành 1 dấu cách duy nhất
 * - Chuẩn hóa Unicode sang dạng NFC (Canonical Decomposition, followed by Canonical Composition)
 * - Chuyển về chữ thường (lowercase) theo Locale.ROOT cho normalizedAlias
 */
public final class WikiAliasNormalizer {

    private WikiAliasNormalizer() {
    }

    /**
     * Chuẩn hóa giá trị hiển thị của alias (trim, collapse space và chuẩn hóa Unicode NFC).
     */
    public static String cleanDisplayAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return "";
        }
        String trimmedAndCollapsed = alias.trim().replaceAll("\\s+", " ");
        return Normalizer.normalize(trimmedAndCollapsed, Normalizer.Form.NFC);
    }

    /**
     * Chuẩn hóa alias phục vụ indexing và tìm kiếm chính xác (NFC + lowercase + trim + collapse space).
     */
    public static String normalize(String alias) {
        String cleaned = cleanDisplayAlias(alias);
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }
}