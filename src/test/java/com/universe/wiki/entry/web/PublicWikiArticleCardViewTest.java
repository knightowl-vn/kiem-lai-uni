package com.universe.wiki.entry.web;

import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("PublicWikiArticleCardView Unit Tests")
class PublicWikiArticleCardViewTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-02T10:00:00Z");

    @Test
    @DisplayName("Prefers direct cover URL when present")
    void shouldPreferDirectCoverUrlWhenPresent() {
        String directUrl = "https://res.cloudinary.com/test/w300.webp";
        PublicWikiArticleCardView card = new PublicWikiArticleCardView(
                ID, "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 30, 40, directUrl
        );

        assertThat(card.displayCoverImageUrl()).isEqualTo(directUrl);
        assertThat(card.fallbackCoverImageUrl()).isEqualTo("/media/assets/" + COVER_ID + "/content");
        assertThat(card.displayCoverImageUrl()).doesNotContain("/variants/");
    }

    @Test
    @DisplayName("Falls back to canonical /content when direct cover URL is absent or blank (never /variants/w300)")
    void shouldFallbackToCanonicalContentWhenDirectUrlAbsent() {
        PublicWikiArticleCardView cardNull = new PublicWikiArticleCardView(
                ID, "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 30, 40, null
        );
        PublicWikiArticleCardView cardBlank = new PublicWikiArticleCardView(
                ID, "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 30, 40, "   "
        );

        assertThat(cardNull.displayCoverImageUrl()).isEqualTo("/media/assets/" + COVER_ID + "/content");
        assertThat(cardBlank.displayCoverImageUrl()).isEqualTo("/media/assets/" + COVER_ID + "/content");
        assertThat(cardNull.displayCoverImageUrl()).doesNotContain("/variants/");
        assertThat(cardBlank.displayCoverImageUrl()).doesNotContain("/variants/");
    }

    @Test
    @DisplayName("Returns null for display and fallback URLs when coverMediaAssetId is null")
    void shouldReturnNullWhenNoCoverAssetId() {
        PublicWikiArticleCardView card = new PublicWikiArticleCardView(
                ID, "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, null, 50, 50, null
        );

        assertThat(card.displayCoverImageUrl()).isNull();
        assertThat(card.fallbackCoverImageUrl()).isNull();
    }

    @Test
    @DisplayName("Formats CSS object-position correctly")
    void shouldFormatObjectPosition() {
        PublicWikiArticleCardView card = new PublicWikiArticleCardView(
                ID, "Tiêu đề", "tieu-de", "CHARACTER", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 35, 65, null
        );

        assertThat(card.coverObjectPosition()).isEqualTo("35% 65%");
    }

    @Test
    @DisplayName("Creates card view correctly from DTO")
    void shouldCreateFromDTO() {
        PublishedWikiArticleListItemDTO dto = new PublishedWikiArticleListItemDTO(
                ID, "Tiêu đề", "tieu-de", "FACTION", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 25, 75
        );

        PublicWikiArticleCardView view = PublicWikiArticleCardView.fromDTO(dto, "https://direct.example/img.jpg");

        assertThat(view.id()).isEqualTo(ID);
        assertThat(view.title()).isEqualTo("Tiêu đề");
        assertThat(view.slug()).isEqualTo("tieu-de");
        assertThat(view.articleType()).isEqualTo("FACTION");
        assertThat(view.summary()).isEqualTo("Tóm tắt");
        assertThat(view.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(view.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(view.coverMediaAssetId()).isEqualTo(COVER_ID);
        assertThat(view.coverPositionX()).isEqualTo(25);
        assertThat(view.coverPositionY()).isEqualTo(75);
        assertThat(view.directCoverUrl()).isEqualTo("https://direct.example/img.jpg");

        assertThatNullPointerException().isThrownBy(() -> PublicWikiArticleCardView.fromDTO(null, null));
    }

    @Test
    @DisplayName("Obeys standard Java record equality contract and never equals a DTO of another type")
    void shouldObeyStandardJavaEqualityContract() {
        PublishedWikiArticleListItemDTO dto = new PublishedWikiArticleListItemDTO(
                ID, "Tiêu đề", "tieu-de", "FACTION", "Tóm tắt",
                PUBLISHED_AT, UPDATED_AT, COVER_ID, 25, 75
        );
        PublicWikiArticleCardView view1 = PublicWikiArticleCardView.fromDTO(dto, "https://direct.url");
        PublicWikiArticleCardView view2 = PublicWikiArticleCardView.fromDTO(dto, "https://direct.url");
        PublicWikiArticleCardView view3 = PublicWikiArticleCardView.fromDTO(dto, "https://direct.url");

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
        PublicWikiArticleCardView differentUrl = PublicWikiArticleCardView.fromDTO(dto, "https://different.url");
        assertThat(view1).isNotEqualTo(differentUrl);

        assertThat(view1.equals(null)).isFalse();
        assertThat(view1.equals("different object")).isFalse();
    }
}
