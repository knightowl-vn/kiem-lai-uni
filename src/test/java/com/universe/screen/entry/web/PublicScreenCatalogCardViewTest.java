package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("PublicScreenCatalogCardView Unit Tests")
class PublicScreenCatalogCardViewTest {

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    @DisplayName("Should compose poster delivery URLs and Vietnamese labels from DTO")
    void shouldComposePosterUrlsAndDisplayLabels() {
        UUID posterId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        PublicScreenCatalogItemDTO dto = new PublicScreenCatalogItemDTO(
                "kiem-lai-1",
                "Kiếm Lai 1",
                "Original",
                "Synopsis",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                now
        );

        PublicScreenCatalogCardView cardView = PublicScreenCatalogCardView.fromDTO(dto);

        assertThat(cardView.slug()).isEqualTo("kiem-lai-1");
        assertThat(cardView.title()).isEqualTo("Kiếm Lai 1");
        assertThat(cardView.originalTitle()).isEqualTo("Original");
        assertThat(cardView.synopsis()).isEqualTo("Synopsis");
        assertThat(cardView.structure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(cardView.presentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(cardView.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(cardView.publishedAt()).isEqualTo(now);

        // Poster delivery URLs through Media capability
        assertThat(cardView.displayPosterUrl()).isEqualTo("/media/assets/" + posterId + "/variants/w300");
        assertThat(cardView.fallbackPosterUrl()).isEqualTo("/media/assets/" + posterId + "/content");

        // Vietnamese display labels
        assertThat(cardView.structureDisplayName()).isEqualTo("Phim bộ");
        assertThat(cardView.presentationDisplayName()).isEqualTo("Hoạt hình 3D");
        assertThat(cardView.episodeBadgeText()).isEqualTo("Chưa có tập");
    }

    @Test
    @DisplayName("Should format episode badge text for SERIES and FEATURE according to owner specification")
    void shouldFormatEpisodeBadgeText() {
        // Latest published episode = 12, total planned = 24 -> Tập 12/24
        PublicScreenCatalogCardView seriesKnownTotal = new PublicScreenCatalogCardView(
                "series-1", "Series 1", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 12, 24
        );
        assertThat(seriesKnownTotal.episodeBadgeText()).isEqualTo("Tập 12/24");

        // Latest published episode = 12, total unknown -> Tập 12/??
        PublicScreenCatalogCardView seriesUnknownTotal = new PublicScreenCatalogCardView(
                "series-2", "Series 2", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 12, null
        );
        assertThat(seriesUnknownTotal.episodeBadgeText()).isEqualTo("Tập 12/??");

        // Latest published episode = 5, total planned = 20 -> Tập 5/20
        PublicScreenCatalogCardView seriesEp5Total20 = new PublicScreenCatalogCardView(
                "series-3", "Series 3", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 5, 20
        );
        assertThat(seriesEp5Total20.episodeBadgeText()).isEqualTo("Tập 5/20");

        // Latest published episode = 5, total unknown -> Tập 5/??
        PublicScreenCatalogCardView seriesEp5UnknownTotal = new PublicScreenCatalogCardView(
                "series-4", "Series 4", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 5, null
        );
        assertThat(seriesEp5UnknownTotal.episodeBadgeText()).isEqualTo("Tập 5/??");

        // SERIES with no published episodes -> Chưa có tập
        PublicScreenCatalogCardView seriesNoEpisodes = new PublicScreenCatalogCardView(
                "series-0", "Series 0", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, null, 24
        );
        assertThat(seriesNoEpisodes.episodeBadgeText()).isEqualTo("Chưa có tập");

        // FEATURE always returns "Phim lẻ"
        PublicScreenCatalogCardView feature = new PublicScreenCatalogCardView(
                "feature-1", "Feature 1", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, now, null, null
        );
        assertThat(feature.episodeBadgeText()).isEqualTo("Phim lẻ");

        // Inconsistent legacy safety: total smaller than latest published episode -> falls back safely to Tập 30/??
        PublicScreenCatalogCardView inconsistentLegacy = new PublicScreenCatalogCardView(
                "series-inconsistent", "Series Inconsistent", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 30, 24
        );
        assertThat(inconsistentLegacy.episodeBadgeText()).isEqualTo("Tập 30/??");
    }

    @Test
    @DisplayName("hasOriginalTitle: true only if non-blank and not identical to display title")
    void shouldEvaluateHasOriginalTitle() {
        PublicScreenCatalogCardView withDiffOriginal = new PublicScreenCatalogCardView(
                "slug", "Kiếm Lai", "剑来", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 1, 24
        );
        assertThat(withDiffOriginal.hasOriginalTitle()).isTrue();

        PublicScreenCatalogCardView withNullOriginal = new PublicScreenCatalogCardView(
                "slug", "Kiếm Lai", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 1, 24
        );
        assertThat(withNullOriginal.hasOriginalTitle()).isFalse();

        PublicScreenCatalogCardView withBlankOriginal = new PublicScreenCatalogCardView(
                "slug", "Kiếm Lai", "   ", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 1, 24
        );
        assertThat(withBlankOriginal.hasOriginalTitle()).isFalse();

        PublicScreenCatalogCardView withSameOriginal = new PublicScreenCatalogCardView(
                "slug", "Kiếm Lai", "Kiếm Lai", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 1, 24
        );
        assertThat(withSameOriginal.hasOriginalTitle()).isFalse();

        PublicScreenCatalogCardView withCaseInsensitiveSameOriginal = new PublicScreenCatalogCardView(
                "slug", "Sword Coming", "  sword coming  ", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now, 1, 24
        );
        assertThat(withCaseInsensitiveSameOriginal.hasOriginalTitle()).isFalse();
    }

    @Test
    @DisplayName("Should handle missing poster gracefully with null URLs")
    void shouldHandleMissingPosterGracefully() {
        PublicScreenCatalogItemDTO dto = new PublicScreenCatalogItemDTO(
                "movie-1",
                "Movie 1",
                null,
                null,
                ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION,
                null,
                now
        );

        PublicScreenCatalogCardView cardView = PublicScreenCatalogCardView.fromDTO(dto);

        assertThat(cardView.posterMediaAssetId()).isNull();
        assertThat(cardView.displayPosterUrl()).isNull();
        assertThat(cardView.fallbackPosterUrl()).isNull();
        assertThat(cardView.structureDisplayName()).isEqualTo("Phim lẻ");
        assertThat(cardView.presentationDisplayName()).isEqualTo("Người đóng");
    }

    @Test
    @DisplayName("Constructor enforces required non-null invariants")
    void constructorEnforcesInvariants() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PublicScreenCatalogCardView(null, "Title", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now))
                .withMessageContaining("Slug cannot be null.");

        assertThatNullPointerException()
                .isThrownBy(() -> new PublicScreenCatalogCardView("slug", null, null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now))
                .withMessageContaining("Title cannot be null.");

        assertThatNullPointerException()
                .isThrownBy(() -> new PublicScreenCatalogCardView("slug", "Title", null, null, null, ScreenPresentation.ANIMATION_3D, null, now))
                .withMessageContaining("Structure cannot be null.");

        assertThatNullPointerException()
                .isThrownBy(() -> new PublicScreenCatalogCardView("slug", "Title", null, null, ScreenStructure.SERIES, null, null, now))
                .withMessageContaining("Presentation cannot be null.");
    }

    @Test
    @DisplayName("PublicScreenCatalogPageView should correctly distinguish empty catalog from out-of-range requested page")
    void pageViewDistinguishesEmptyCatalogFromOutOfRange() {
        // Empty catalog: 0 published titles exist
        PublicScreenCatalogPageView emptyCatalog = new PublicScreenCatalogPageView(
                java.util.List.of(), 0, 12, 0L, 0, true, true
        );
        assertThat(emptyCatalog.isEmptyCatalog()).isTrue();
        assertThat(emptyCatalog.isOutOfRange()).isFalse();

        // Normal catalog page with items: 24 published titles exist, page 0 has items
        PublicScreenCatalogCardView sampleCard = new PublicScreenCatalogCardView(
                "slug", "Title", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now
        );
        PublicScreenCatalogPageView normalPage = new PublicScreenCatalogPageView(
                java.util.List.of(sampleCard), 0, 12, 24L, 2, true, false
        );
        assertThat(normalPage.isEmptyCatalog()).isFalse();
        assertThat(normalPage.isOutOfRange()).isFalse();

        // Out-of-range requested page: 24 published titles exist, but requested page 5 has no items
        PublicScreenCatalogPageView outOfRangePage = new PublicScreenCatalogPageView(
                java.util.List.of(), 5, 12, 24L, 2, false, true
        );
        assertThat(outOfRangePage.isEmptyCatalog()).isFalse();
        assertThat(outOfRangePage.isOutOfRange()).isTrue();
    }
}
