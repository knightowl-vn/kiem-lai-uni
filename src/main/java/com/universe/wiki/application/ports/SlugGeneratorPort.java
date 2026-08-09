package com.universe.wiki.application.ports;

import com.universe.wiki.domain.article.Slug;

/**
 * Port tạo slug từ chuỗi nguồn.
 *
 * Ví dụ:
 * "Trần Bình An"
 * → "tran-binh-an"
 */
public interface SlugGeneratorPort {

    Slug generate(
            String source
    );
}