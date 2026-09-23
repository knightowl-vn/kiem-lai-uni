package com.universe.wiki.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WikiCoverDTOContractsTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ARTICLE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID USER_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Test
    @DisplayName("WikiArticleDTO trả về đúng variant 300 và fallback URL khi có coverMediaAssetId")
    void shouldReturnCorrectUrlsForWikiArticleDTO() {
        WikiArticleDTO withCover = new WikiArticleDTO(
                ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Tóm tắt", "Nội dung",
                "PUBLISHED", USER_ID, USER_ID, USER_ID, null, Instant.now(), Instant.now(), Instant.now(), null,
                1L, 1L, ASSET_ID
        );

        assertThat(withCover.coverMediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(withCover.displayCoverImageUrl()).isEqualTo("/media/assets/11111111-2222-3333-4444-555555555555/variants/w300");
        assertThat(withCover.fallbackCoverImageUrl()).isEqualTo("/media/assets/11111111-2222-3333-4444-555555555555/content");

        WikiArticleDTO withoutCover = new WikiArticleDTO(
                ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Tóm tắt", "Nội dung",
                "DRAFT", USER_ID, USER_ID, null, null, Instant.now(), Instant.now(), null, null,
                1L, 1L, null
        );

        assertThat(withoutCover.coverMediaAssetId()).isNull();
        assertThat(withoutCover.displayCoverImageUrl()).isNull();
        assertThat(withoutCover.fallbackCoverImageUrl()).isNull();
    }

    @Test
    @DisplayName("PublishedWikiArticleDTO trả về đúng variant 300 và fallback URL khi có coverMediaAssetId")
    void shouldReturnCorrectUrlsForPublishedWikiArticleDTO() {
        PublishedWikiArticleDTO withCover = new PublishedWikiArticleDTO(
                ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Tóm tắt", "Nội dung",
                Instant.now(), Instant.now(), ASSET_ID
        );

        assertThat(withCover.coverMediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(withCover.displayCoverImageUrl()).isEqualTo("/media/assets/11111111-2222-3333-4444-555555555555/variants/w300");
        assertThat(withCover.fallbackCoverImageUrl()).isEqualTo("/media/assets/11111111-2222-3333-4444-555555555555/content");

        PublishedWikiArticleDTO withoutCover = new PublishedWikiArticleDTO(
                ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Tóm tắt", "Nội dung",
                Instant.now(), Instant.now(), null
        );

        assertThat(withoutCover.coverMediaAssetId()).isNull();
        assertThat(withoutCover.displayCoverImageUrl()).isNull();
        assertThat(withoutCover.fallbackCoverImageUrl()).isNull();
    }

    @Test
    @DisplayName("PublishedWikiArticleListItemDTO trả về đúng variant 300 và fallback URL khi có coverMediaAssetId")
    void shouldReturnCorrectUrlsForPublishedWikiArticleListItemDTO() {
        PublishedWikiArticleListItemDTO withCover = new PublishedWikiArticleListItemDTO(
                ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Tóm tắt",
                Instant.now(), Instant.now(), ASSET_ID
        );

        assertThat(withCover.coverMediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(withCover.displayCoverImageUrl()).isEqualTo("/media/assets/11111111-2222-3333-4444-555555555555/variants/w300");
        assertThat(withCover.fallbackCoverImageUrl()).isEqualTo("/media/assets/11111111-2222-3333-4444-555555555555/content");

        PublishedWikiArticleListItemDTO withoutCover = new PublishedWikiArticleListItemDTO(
                ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "Tóm tắt",
                Instant.now(), Instant.now(), null
        );

        assertThat(withoutCover.coverMediaAssetId()).isNull();
        assertThat(withoutCover.displayCoverImageUrl()).isNull();
        assertThat(withoutCover.fallbackCoverImageUrl()).isNull();
    }
}
