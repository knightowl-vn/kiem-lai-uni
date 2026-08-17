package com.universe.wiki.infrastructure.markdown;

import org.junit.jupiter.api.Test;

import java.util.Set;

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
}