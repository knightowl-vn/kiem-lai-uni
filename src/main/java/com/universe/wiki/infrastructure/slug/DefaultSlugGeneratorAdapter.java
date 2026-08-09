package com.universe.wiki.infrastructure.slug;

import com.universe.wiki.application.ports.SlugGeneratorPort;
import com.universe.wiki.domain.article.Slug;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Adapter tạo slug từ tiêu đề bài viết.
 *
 * Ví dụ:
 * "Trần Bình An"
 * → "tran-binh-an"
 */
@Component
public class DefaultSlugGeneratorAdapter
        implements SlugGeneratorPort {

    private static final Pattern
            DIACRITICAL_MARKS_PATTERN =
            Pattern.compile("\\p{M}+");

    private static final Pattern
            NON_ALPHANUMERIC_PATTERN =
            Pattern.compile("[^a-z0-9]+");

    private static final Pattern
            EDGE_HYPHENS_PATTERN =
            Pattern.compile("(^-+)|(-+$)");

    @Override
    public Slug generate(
            String source
    ) {
        if (source == null
                || source.isBlank()) {

            throw new IllegalArgumentException(
                    "Nguồn tạo slug không được để trống."
            );
        }

        String normalizedSource =
                source.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        /*
         * Normalizer xử lý các ký tự có dấu như:
         * ầ → a + dấu
         * ì → i + dấu
         *
         * Tuy nhiên ký tự đ không được chuyển tự động,
         * nên phải xử lý riêng.
         */
        normalizedSource =
                normalizedSource
                        .replace('đ', 'd');

        String withoutDiacritics =
                DIACRITICAL_MARKS_PATTERN
                        .matcher(
                                Normalizer.normalize(
                                        normalizedSource,
                                        Normalizer.Form.NFD
                                )
                        )
                        .replaceAll("");

        /*
         * Thay mọi nhóm ký tự không phải chữ hoặc số
         * bằng một dấu gạch ngang.
         */
        String generatedValue =
                NON_ALPHANUMERIC_PATTERN
                        .matcher(
                                withoutDiacritics
                        )
                        .replaceAll("-");

        /*
         * Xóa dấu gạch ngang dư ở đầu hoặc cuối.
         */
        generatedValue =
                EDGE_HYPHENS_PATTERN
                        .matcher(
                                generatedValue
                        )
                        .replaceAll("");

        if (generatedValue.isBlank()) {
            throw new IllegalArgumentException(
                    "Không thể tạo slug hợp lệ từ dữ liệu đã cung cấp."
            );
        }

        return new Slug(
                generatedValue
        );
    }
}