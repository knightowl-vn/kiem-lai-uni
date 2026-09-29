package com.universe.wiki.application.article.query.search;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Tiện ích chuẩn hóa chuỗi tìm kiếm điều hướng Wiki.
 *
 * Hỗ trợ:
 * - Trim khoảng trắng đầu/cuối và gộp nhiều khoảng trắng liên tiếp
 * - Chuẩn hóa Unicode sang dạng NFC
 * - Giới hạn độ dài tối đa 200 ký tự
 * - Gập ký tự Đ/đ sang d và bóc tách dấu tiếng Việt phục vụ so khớp không dấu
 * - Escape ký tự đặc biệt của biểu thức LIKE (%, _, \\)
 */
public final class WikiSearchNormalizer {

    public static final int MAX_QUERY_LENGTH = 200;

    private WikiSearchNormalizer() {
    }

    /**
     * Chuẩn hóa từ khóa hiển thị: trim, collapse spaces, NFC, tối đa 200 ký tự.
     */
    public static String cleanQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String trimmed = query.trim().replaceAll("\\s+", " ");
        String nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        if (nfc.length() > MAX_QUERY_LENGTH) {
            nfc = nfc.substring(0, MAX_QUERY_LENGTH).trim();
        }
        return nfc;
    }

    /**
     * Gập chuỗi sang dạng không dấu, viết thường, gập Đ/đ -> d phục vụ xếp hạng và so khớp không phân biệt dấu.
     */
    public static String fold(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String cleaned = cleanQuery(input);
        if (cleaned.isEmpty()) {
            return "";
        }
        String dFolded = cleaned.replace('Đ', 'd').replace('đ', 'd');
        String nfd = Normalizer.normalize(dFolded, Normalizer.Form.NFD);
        String stripped = nfd.replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * Thoát ký tự đặc biệt cho câu lệnh LIKE SQL / JPQL.
     */
    public static String escapeLike(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
