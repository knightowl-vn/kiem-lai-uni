package com.universe.wiki.domain.article;

import java.util.Locale;
import java.util.Objects;

/**
 * Value Object đại diện cho slug của một bài viết Wiki.
 *
 * Ví dụ hợp lệ:
 * - tran-binh-an
 * - kiem-khi-truong-thanh
 * - lac-phach-son
 */
public record Slug(
        String value
) {

    private static final int MAX_LENGTH =
            180;

    private static final String VALID_PATTERN =
            "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public Slug {
        Objects.requireNonNull(
                value,
                "Slug không được để trống."
        );

        String normalizedValue =
                value.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (normalizedValue.isBlank()) {
            throw new IllegalArgumentException(
                    "Slug không được để trống."
            );
        }

        if (normalizedValue.length()
                > MAX_LENGTH) {

            throw new IllegalArgumentException(
                    "Slug không được vượt quá "
                            + MAX_LENGTH
                            + " ký tự."
            );
        }

        if (!normalizedValue.matches(
                VALID_PATTERN
        )) {
            throw new IllegalArgumentException(
                    "Slug chỉ được chứa chữ thường không dấu, "
                            + "chữ số và dấu gạch ngang."
            );
        }

        value =
                normalizedValue;
    }

    @Override
    public String toString() {
        return value;
    }
}