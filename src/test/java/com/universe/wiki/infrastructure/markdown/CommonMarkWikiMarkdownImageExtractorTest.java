package com.universe.wiki.infrastructure.markdown;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonMarkWikiMarkdownImageExtractorTest {

    private final CommonMarkWikiMarkdownImageExtractor
            extractor =
            new CommonMarkWikiMarkdownImageExtractor();

    @Test
    void shouldExtractWikiImageUrls() {

        String markdown = """
                Nội dung test.

                ![Ảnh A](https://example.com/a.webp)

                ![Ảnh B](https://example.com/b.webp)
                """;

        Set<String> urls =
                extractor.extractImageUrls(
                        markdown
                );

        assertEquals(
                2,
                urls.size()
        );

        assertTrue(
                urls.contains(
                        "https://example.com/a.webp"
                )
        );

        assertTrue(
                urls.contains(
                        "https://example.com/b.webp"
                )
        );
    }

    @Test
    void shouldRemoveDuplicateUrls() {

        String markdown = """
                ![A](https://example.com/a.webp)

                ![A again](https://example.com/a.webp)
                """;

        Set<String> urls =
                extractor.extractImageUrls(
                        markdown
                );

        assertEquals(
                1,
                urls.size()
        );
    }

    @Test
    void shouldReturnEmptySetWhenNoImageExists() {

        String markdown = """
                ## Tiêu đề

                Đây chỉ là nội dung.
                """;

        Set<String> urls =
                extractor.extractImageUrls(
                        markdown
                );

        assertTrue(
                urls.isEmpty()
        );
    }
    
    @Test
    void shouldExtractUrlFromWikiImageSyntax() {

        String markdown = """
                ![anh1](https://res.cloudinary.com/test/image.webp "wiki:size=medium;layout=wrap-right")
                """;

        Set<String> urls =
                extractor.extractImageUrls(
                        markdown
                );

        assertEquals(
                Set.of(
                        "https://res.cloudinary.com/test/image.webp"
                ),
                urls
        );
    }

    @Test
    @DisplayName("Trích xuất chính xác đường dẫn Media-backed /media/assets/{uuid}/content với metadata wiki")
    void shouldExtractMediaBackedImageUrlsWithWikiMetadata() {
        String mediaUrl = "/media/assets/3b999d3e-9080-48e0-bb15-0d29ca365287/content";
        String markdown = """
                # Trần Bình An

                ![Chân dung Trần Bình An](""" + mediaUrl + """
                 "wiki:width=60;layout=block-center")
                *Kiếm khí trường tồn*
                """;

        Set<String> urls = extractor.extractImageUrls(markdown);

        assertThat(urls).containsExactly(mediaUrl);
    }

    @Test
    @DisplayName("Trích xuất cả Media URLs và legacy Cloudinary URLs khi cùng xuất hiện trong một bài viết")
    void shouldExtractBothMediaAndLegacyUrls() {
        String mediaUrl = "/media/assets/11111111-1111-1111-1111-111111111111/content";
        String legacyUrl = "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp";

        String markdown = """
                ![Media](""" + mediaUrl + """
                )

                ![Legacy](""" + legacyUrl + """
                )
                """;

        Set<String> urls = extractor.extractImageUrls(markdown);

        assertThat(urls).containsExactlyInAnyOrder(mediaUrl, legacyUrl);
    }
}