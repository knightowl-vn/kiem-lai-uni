package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("PublicWikiArticleDetailView Unit Tests")
class PublicWikiArticleDetailViewTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CREATOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID UPDATER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-02T10:00:00Z");

    @Test
    @DisplayName("Prefers direct cover URL when present")
    void shouldPreferDirectCoverUrlWhenPresent() {
        String directUrl = "https://res.cloudinary.com/test/w300_detail.webp";
        PublicWikiArticleDetailView detail = new PublicWikiArticleDetailView(
                ID, "Chi tiết", "chi-tiet", "CHARACTER", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 40, 60, 2L,
                CREATOR_ID, UPDATER_ID, directUrl
        );

        assertThat(detail.displayCoverImageUrl()).isEqualTo(directUrl);
        assertThat(detail.fallbackCoverImageUrl()).isEqualTo("/media/assets/" + COVER_ID + "/content");
        assertThat(detail.displayCoverImageUrl()).doesNotContain("/variants/");
    }

    @Test
    @DisplayName("Falls back to canonical /content when direct cover URL is absent or blank (never /variants/w300)")
    void shouldFallbackToCanonicalContentWhenDirectUrlAbsent() {
        PublicWikiArticleDetailView detailNull = new PublicWikiArticleDetailView(
                ID, "Chi tiết", "chi-tiet", "CHARACTER", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 40, 60, 2L,
                CREATOR_ID, UPDATER_ID, null
        );
        PublicWikiArticleDetailView detailBlank = new PublicWikiArticleDetailView(
                ID, "Chi tiết", "chi-tiet", "CHARACTER", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 40, 60, 2L,
                CREATOR_ID, UPDATER_ID, "   "
        );

        assertThat(detailNull.displayCoverImageUrl()).isEqualTo("/media/assets/" + COVER_ID + "/content");
        assertThat(detailBlank.displayCoverImageUrl()).isEqualTo("/media/assets/" + COVER_ID + "/content");
        assertThat(detailNull.displayCoverImageUrl()).doesNotContain("/variants/");
        assertThat(detailBlank.displayCoverImageUrl()).doesNotContain("/variants/");
    }

    @Test
    @DisplayName("Returns null for display and fallback URLs when coverMediaAssetId is null")
    void shouldReturnNullWhenNoCoverAssetId() {
        PublicWikiArticleDetailView detail = new PublicWikiArticleDetailView(
                ID, "Chi tiết", "chi-tiet", "CHARACTER", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, null, 50, 50, 1L,
                CREATOR_ID, null, null
        );

        assertThat(detail.displayCoverImageUrl()).isNull();
        assertThat(detail.fallbackCoverImageUrl()).isNull();
    }

    @Test
    @DisplayName("Formats CSS object-position correctly")
    void shouldFormatObjectPosition() {
        PublicWikiArticleDetailView detail = new PublicWikiArticleDetailView(
                ID, "Chi tiết", "chi-tiet", "CHARACTER", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 45, 55, 1L,
                CREATOR_ID, null, null
        );

        assertThat(detail.coverObjectPosition()).isEqualTo("45% 55%");
    }

    @Test
    @DisplayName("Creates detail view correctly from DTO")
    void shouldCreateFromDTO() {
        PublishedWikiArticleDTO dto = new PublishedWikiArticleDTO(
                ID, "Chi tiết", "chi-tiet", "REALM", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 20, 80, 3L,
                CREATOR_ID, UPDATER_ID
        );

        PublicWikiArticleDetailView view = PublicWikiArticleDetailView.fromDTO(dto, "https://direct.example/cover.jpg");

        assertThat(view.id()).isEqualTo(ID);
        assertThat(view.title()).isEqualTo("Chi tiết");
        assertThat(view.slug()).isEqualTo("chi-tiet");
        assertThat(view.articleType()).isEqualTo("REALM");
        assertThat(view.summary()).isEqualTo("Tóm tắt");
        assertThat(view.content()).isEqualTo("Nội dung");
        assertThat(view.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(view.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(view.coverMediaAssetId()).isEqualTo(COVER_ID);
        assertThat(view.coverPositionX()).isEqualTo(20);
        assertThat(view.coverPositionY()).isEqualTo(80);
        assertThat(view.contentVersion()).isEqualTo(3L);
        assertThat(view.createdBy()).isEqualTo(CREATOR_ID);
        assertThat(view.updatedBy()).isEqualTo(UPDATER_ID);
        assertThat(view.directCoverUrl()).isEqualTo("https://direct.example/cover.jpg");

        assertThatNullPointerException().isThrownBy(() -> PublicWikiArticleDetailView.fromDTO(null, null));
    }

    @Test
    @DisplayName("Obeys standard Java record equality contract and never equals a DTO of another type")
    void shouldObeyStandardJavaEqualityContract() {
        PublishedWikiArticleDTO dto = new PublishedWikiArticleDTO(
                ID, "Chi tiết", "chi-tiet", "REALM", "Tóm tắt", "Nội dung",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 20, 80, 3L,
                CREATOR_ID, UPDATER_ID
        );
        PublicWikiArticleDetailView view1 = PublicWikiArticleDetailView.fromDTO(dto, "https://direct.url");
        PublicWikiArticleDetailView view2 = PublicWikiArticleDetailView.fromDTO(dto, "https://direct.url");
        PublicWikiArticleDetailView view3 = PublicWikiArticleDetailView.fromDTO(dto, "https://direct.url");

        // Reflexive
        assertThat(view1).isEqualTo(view1);
        // Symmetric
        assertThat(view1).isEqualTo(view2);
        assertThat(view2).isEqualTo(view1);
        // Transitive
        assertThat(view2).isEqualTo(view3);
        assertThat(view1).isEqualTo(view3);
        // Consistent hashCode
        assertThat(view1.hashCode()).isEqualTo(view2.hashCode());

        // Never equal to DTO of another type (preserves equality symmetry with DTO)
        assertThat(view1.equals(dto)).isFalse();
        assertThat(dto.equals(view1)).isFalse();

        // Unequal on differing component
        PublicWikiArticleDetailView differentUrl = PublicWikiArticleDetailView.fromDTO(dto, "https://different.url");
        assertThat(view1).isNotEqualTo(differentUrl);

        assertThat(view1.equals(null)).isFalse();
        assertThat(view1.equals("different object")).isFalse();
    }
}
