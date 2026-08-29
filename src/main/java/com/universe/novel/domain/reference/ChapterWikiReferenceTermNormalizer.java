package com.universe.novel.domain.reference;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * Tiện ích chuẩn hóa thuật ngữ tham chiếu Wiki thuộc Novel module.
 *
 * Quy tắc chuẩn hóa:
 * - Unicode NFC
 * - Trim khoảng trắng đầu/cuối
 * - Thu gọn khoảng trắng liên tiếp bên trong thành một dấu cách
 * - Chuyển sang chữ thường với Locale.ROOT cho normalizedTerm
 * - Độ dài sau khi chuẩn hóa phải từ 1 đến 100 ký tự.
 */
public final class ChapterWikiReferenceTermNormalizer {

    public static final int MIN_TERM_LENGTH = 1;
    public static final int MAX_TERM_LENGTH = 100;

    private ChapterWikiReferenceTermNormalizer() {
    }

    /**
     * Chuẩn hóa hiển thị của thuật ngữ (Unicode NFC, trim, collapse whitespace).
     */
    public static String normalizeDisplayTerm(String rawTerm) {
        if (rawTerm == null) {
            throw new IllegalArgumentException("Thuật ngữ không được để trống.");
        }

        String nfc = Normalizer.normalize(rawTerm, Normalizer.Form.NFC);
        String trimmed = nfc.trim();
        String collapsed = trimmed.replaceAll("\\s+", " ");

        if (collapsed.isEmpty()) {
            throw new IllegalArgumentException("Thuật ngữ không được để trống.");
        }

        if (collapsed.length() > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException(
                    "Thuật ngữ không được vượt quá " + MAX_TERM_LENGTH + " ký tự."
            );
        }

        return collapsed;
    }

    /**
     * Chuẩn hóa để so khớp / đánh index (display term dạng chữ thường theo Locale.ROOT).
     */
    public static String normalizeSearchKey(String rawTerm) {
        String displayTerm = normalizeDisplayTerm(rawTerm);
        return displayTerm.toLowerCase(Locale.ROOT);
    }
}
