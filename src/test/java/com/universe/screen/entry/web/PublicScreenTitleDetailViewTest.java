package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseInstallmentDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicScreenTitleDetailView Unit Tests")
class PublicScreenTitleDetailViewTest {

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @Test
    @DisplayName("Should resolve poster and backdrop delivery URLs")
    void shouldResolvePosterAndBackdropUrls() {
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        PublicScreenTitleDetailDTO dto = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1", "剑来",
                "Tóm tắt", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, now, 12, 24, List.of(), null
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(dto);

        assertThat(view.displayPosterUrl()).isEqualTo("/media/assets/" + posterId + "/variants/w300");
        assertThat(view.fallbackPosterUrl()).isEqualTo("/media/assets/" + posterId + "/content");
        assertThat(view.displayBackdropUrl()).isEqualTo("/media/assets/" + backdropId + "/variants/w1200");
        assertThat(view.fallbackBackdropUrl()).isEqualTo("/media/assets/" + backdropId + "/content");
        assertThat(view.hasBackdrop()).isTrue();
    }

    @Test
    @DisplayName("Should return null poster/backdrop URLs when asset IDs are null")
    void shouldReturnNullWhenAssetIdsAreNull() {
        PublicScreenTitleDetailDTO dto = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1", null,
                null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION,
                null, null, now, null, null, List.of(), null
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(dto);

        assertThat(view.displayPosterUrl()).isNull();
        assertThat(view.fallbackPosterUrl()).isNull();
        assertThat(view.displayBackdropUrl()).isNull();
        assertThat(view.fallbackBackdropUrl()).isNull();
        assertThat(view.hasBackdrop()).isFalse();
    }

    @Test
    @DisplayName("Should format structure and presentation Vietnamese display names")
    void shouldFormatStructureAndPresentationNames() {
        PublicScreenTitleDetailDTO s3d = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s-3d", "Series 3D", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), null
        );
        PublicScreenTitleDetailView v1 = PublicScreenTitleDetailView.fromDTO(s3d);
        assertThat(v1.structureDisplayName()).isEqualTo("Phim bộ");
        assertThat(v1.presentationDisplayName()).isEqualTo("Hoạt hình 3D");

        PublicScreenTitleDetailDTO fLive = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "f-live", "Feature Live", null,
                null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION,
                null, null, now, null, null, List.of(), null
        );
        PublicScreenTitleDetailView v2 = PublicScreenTitleDetailView.fromDTO(fLive);
        assertThat(v2.structureDisplayName()).isEqualTo("Phim lẻ");
        assertThat(v2.presentationDisplayName()).isEqualTo("Người đóng");
    }

    @Test
    @DisplayName("Should produce correct episode badge text across various edge cases")
    void shouldProduceEpisodeBadgeText() {
        // FEATURE
        PublicScreenTitleDetailDTO feature = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "feat", "Feature", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(feature).episodeBadgeText()).isEqualTo("Phim lẻ");

        // SERIES with no published episodes
        PublicScreenTitleDetailDTO noEp = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "no-ep", "No Ep", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, 24, List.of(), null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(noEp).episodeBadgeText()).isEqualTo("Chưa có tập");

        // SERIES with known total
        PublicScreenTitleDetailDTO knownTotal = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "known", "Known", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 12, 24, List.of(), null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(knownTotal).episodeBadgeText()).isEqualTo("Tập 12/24");

        // SERIES with unknown total
        PublicScreenTitleDetailDTO unknownTotal = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "unknown", "Unknown", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 12, null, List.of(), null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(unknownTotal).episodeBadgeText()).isEqualTo("Tập 12/??");

        // SERIES with inconsistent legacy total < latest
        PublicScreenTitleDetailDTO inconsistentTotal = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "incon", "Incon", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 12, 10, List.of(), null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(inconsistentTotal).episodeBadgeText()).isEqualTo("Tập 12/??");
    }

    @Test
    @DisplayName("Should detect hasOriginalTitle accurately")
    void shouldDetectHasOriginalTitle() {
        PublicScreenTitleDetailDTO d1 = new PublicScreenTitleDetailDTO(UUID.randomUUID(), "s", "Title", "Original", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, null, null, List.of(), null);
        assertThat(PublicScreenTitleDetailView.fromDTO(d1).hasOriginalTitle()).isTrue();

        PublicScreenTitleDetailDTO d2 = new PublicScreenTitleDetailDTO(UUID.randomUUID(), "s", "Title", "title", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, null, null, List.of(), null);
        assertThat(PublicScreenTitleDetailView.fromDTO(d2).hasOriginalTitle()).isFalse();

        PublicScreenTitleDetailDTO d3 = new PublicScreenTitleDetailDTO(UUID.randomUUID(), "s", "Title", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, null, null, List.of(), null);
        assertThat(PublicScreenTitleDetailView.fromDTO(d3).hasOriginalTitle()).isFalse();

        PublicScreenTitleDetailDTO d4 = new PublicScreenTitleDetailDTO(UUID.randomUUID(), "s", "Title", "   ", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, null, null, List.of(), null);
        assertThat(PublicScreenTitleDetailView.fromDTO(d4).hasOriginalTitle()).isFalse();
    }

    @Test
    @DisplayName("Should partition entries into typed groups correctly")
    void shouldPartitionEntriesIntoTypedGroups() {
        UUID titleId = UUID.randomUUID();
        PublicScreenEntryDTO ep = new PublicScreenEntryDTO(UUID.randomUUID(), "ep", ScreenEntryType.EPISODE, 1, "E1", null, 1400L, null, true);
        PublicScreenEntryDTO feat = new PublicScreenEntryDTO(UUID.randomUUID(), "feat", ScreenEntryType.FEATURE, null, "F1", null, 7200L, null, true);
        PublicScreenEntryDTO sp = new PublicScreenEntryDTO(UUID.randomUUID(), "sp", ScreenEntryType.SPECIAL, 1, "S1", null, 600L, null, false);
        PublicScreenEntryDTO tr = new PublicScreenEntryDTO(UUID.randomUUID(), "tr", ScreenEntryType.TRAILER, 1, "T1", null, 120L, null, false);

        PublicScreenTitleDetailDTO dto = new PublicScreenTitleDetailDTO(
                titleId, "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 1, null,
                List.of(ep, feat, sp, tr), null
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(dto);

        assertThat(view.hasEntries()).isTrue();
        assertThat(view.episodes()).extracting(PublicScreenEntryView::slug).containsExactly("ep");
        assertThat(view.features()).extracting(PublicScreenEntryView::slug).containsExactly("feat");
        assertThat(view.specials()).extracting(PublicScreenEntryView::slug).containsExactly("sp");
        assertThat(view.trailers()).extracting(PublicScreenEntryView::slug).containsExactly("tr");
    }

    @Test
    @DisplayName("Should correctly evaluate franchise presence")
    void shouldEvaluateFranchisePresence() {
        PublicScreenTitleDetailDTO noFranchise = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(noFranchise).hasFranchise()).isFalse();

        PublicScreenFranchiseDTO franchise = new PublicScreenFranchiseDTO(
                UUID.randomUUID(), "Kiếm Lai", "kiem-lai",
                List.of(new PublicScreenFranchiseInstallmentDTO(UUID.randomUUID(), "p1", "Phần 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, true))
        );
        PublicScreenTitleDetailDTO withFranchise = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(franchise)
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(withFranchise).hasFranchise()).isTrue();
        assertThat(PublicScreenTitleDetailView.fromDTO(withFranchise).hasFranchises()).isTrue();
    }

    @Test
    @DisplayName("Should handle multiple franchises in view model")
    void shouldHandleMultipleFranchisesCorrectly() {
        PublicScreenFranchiseDTO f1 = new PublicScreenFranchiseDTO(
                UUID.randomUUID(), "Franchise 1", "f1",
                List.of(new PublicScreenFranchiseInstallmentDTO(UUID.randomUUID(), "p1", "Phần 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, true))
        );
        PublicScreenFranchiseDTO f2 = new PublicScreenFranchiseDTO(
                UUID.randomUUID(), "Franchise 2", "f2",
                List.of(new PublicScreenFranchiseInstallmentDTO(UUID.randomUUID(), "p2", "Phần 2", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, false))
        );

        PublicScreenTitleDetailDTO dto = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(f1, f2)
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(dto);

        assertThat(view.hasFranchises()).isTrue();
        assertThat(view.hasFranchise()).isTrue();
        assertThat(view.franchises()).containsExactly(f1, f2);
        assertThat(view.franchise()).isEqualTo(f1);
    }

    @Test
    @DisplayName("Should evaluate airing status methods accurately")
    void shouldEvaluateAiringStatusMethods() {
        PublicScreenTitleDetailDTO withAiring = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                com.universe.screen.domain.ScreenAiringStatus.ONGOING,
                null, null, null, null, null, null
        );
        PublicScreenTitleDetailView v1 = PublicScreenTitleDetailView.fromDTO(withAiring);
        assertThat(v1.hasAiringStatus()).isTrue();
        assertThat(v1.airingStatusDisplayName()).isEqualTo("Đang chiếu");

        PublicScreenTitleDetailDTO withoutAiring = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        PublicScreenTitleDetailView v2 = PublicScreenTitleDetailView.fromDTO(withoutAiring);
        assertThat(v2.hasAiringStatus()).isFalse();
        assertThat(v2.airingStatusDisplayName()).isNull();
    }

    @Test
    @DisplayName("Should format duration label and value for SERIES and FEATURE")
    void shouldFormatDurationCorrectly() {
        // SERIES
        PublicScreenTitleDetailDTO series = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s", "Series", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                null, null, null, null, null, null, 24
        );
        PublicScreenTitleDetailView vs = PublicScreenTitleDetailView.fromDTO(series);
        assertThat(vs.hasDuration()).isTrue();
        assertThat(vs.formattedDuration()).isEqualTo("~24 phút/tập");

        // FEATURE
        PublicScreenTitleDetailDTO feat = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "f", "Feature", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                null, null, null, null, null, null, 120
        );
        PublicScreenTitleDetailView vf = PublicScreenTitleDetailView.fromDTO(feat);
        assertThat(vf.hasDuration()).isTrue();
        assertThat(vf.durationLabel()).isEqualTo("Thời lượng");
        assertThat(vf.formattedDuration()).isEqualTo("120 phút");

        // Null duration
        PublicScreenTitleDetailDTO noDur = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "n", "No Dur", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        PublicScreenTitleDetailView vn = PublicScreenTitleDetailView.fromDTO(noDur);
        assertThat(vn.hasDuration()).isFalse();
        assertThat(vn.formattedDuration()).isNull();
    }

    @Test
    @DisplayName("Should evaluate hasMetadata and all optional metadata fields correctly")
    void shouldEvaluateMetadataPresence() {
        PublicScreenTitleDetailDTO full = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "m", "Meta", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                com.universe.screen.domain.ScreenAiringStatus.UPCOMING,
                2024, "Trung Quốc", "Phong Hỏa Hí Chư Hầu", "Đạo diễn A", "Studio B", 24
        );
        PublicScreenTitleDetailView vFull = PublicScreenTitleDetailView.fromDTO(full);
        assertThat(vFull.hasMetadata()).isTrue();
        assertThat(vFull.hasReleaseYear()).isTrue();
        assertThat(vFull.hasCountry()).isTrue();
        assertThat(vFull.hasOriginalAuthor()).isTrue();
        assertThat(vFull.hasDirector()).isTrue();
        assertThat(vFull.hasProductionStudio()).isTrue();

        PublicScreenTitleDetailDTO empty = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "e", "Empty", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        PublicScreenTitleDetailView vEmpty = PublicScreenTitleDetailView.fromDTO(empty);
        assertThat(vEmpty.hasMetadata()).isFalse();
        assertThat(vEmpty.hasReleaseYear()).isFalse();
        assertThat(vEmpty.hasCountry()).isFalse();
        assertThat(vEmpty.hasOriginalAuthor()).isFalse();
        assertThat(vEmpty.hasDirector()).isFalse();
        assertThat(vEmpty.hasProductionStudio()).isFalse();
    }

    @Test
    @DisplayName("Should format structureAndPresentationText as plain secondary text")
    void shouldFormatStructureAndPresentationText() {
        PublicScreenTitleDetailDTO series3d = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s-3d", "Series 3D", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(series3d).structureAndPresentationText())
                .isEqualTo("Phim bộ · Hoạt hình 3D");

        PublicScreenTitleDetailDTO featLive = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "f-live", "Feature Live", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION,
                null, null, now, null, null, List.of(), List.of()
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(featLive).structureAndPresentationText())
                .isEqualTo("Phim lẻ · Người đóng");
    }

    @Test
    @DisplayName("Should evaluate hasEpisodeProgressBadge: false for FEATURE, true for SERIES with episodes or total")
    void shouldEvaluateHasEpisodeProgressBadgeCorrectly() {
        // FEATURE: Always false, suppressing redundant progress badge
        PublicScreenTitleDetailDTO feature = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "f", "Feature", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(feature).hasEpisodeProgressBadge()).isFalse();

        // SERIES with published episodes
        PublicScreenTitleDetailDTO seriesWithEp = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s1", "Series Ep", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 5, 24, List.of(), List.of()
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(seriesWithEp).hasEpisodeProgressBadge()).isTrue();

        // SERIES with total count known but 0 published episodes
        PublicScreenTitleDetailDTO seriesTotalOnly = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s2", "Series Total", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, 24, List.of(), List.of()
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(seriesTotalOnly).hasEpisodeProgressBadge()).isTrue();

        // SERIES with neither latest nor total count still displays [Chưa có tập]
        PublicScreenTitleDetailDTO seriesEmpty = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "s3", "Series Empty", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(seriesEmpty).hasEpisodeProgressBadge()).isTrue();
        assertThat(PublicScreenTitleDetailView.fromDTO(seriesEmpty).episodeBadgeText()).isEqualTo("Chưa có tập");
    }

    @Test
    @DisplayName("Should evaluate hasDetailedMetadata: true if duration, releaseYear, country, author, director, or studio is present")
    void shouldEvaluateHasDetailedMetadataCorrectly() {
        // Release year present -> hasDetailedMetadata should be true
        PublicScreenTitleDetailDTO onlyYear = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "y", "Year Only", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                null, 2024, null, null, null, null, null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(onlyYear).hasDetailedMetadata()).isTrue();

        // Country present -> hasDetailedMetadata should be true
        PublicScreenTitleDetailDTO withCountry = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "c", "Country Only", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                null, null, "Trung Quốc", null, null, null, null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(withCountry).hasDetailedMetadata()).isTrue();

        // No metadata present -> hasDetailedMetadata should be false
        PublicScreenTitleDetailDTO empty = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "e", "Empty", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of(),
                null, null, null, null, null, null, null
        );
        assertThat(PublicScreenTitleDetailView.fromDTO(empty).hasDetailedMetadata()).isFalse();
    }

    @Test
    @DisplayName("Should select earliest published episode in ascending order for Watch Now action")
    void shouldSelectEarliestPublishedEpisodeForWatchNow() {
        PublicScreenEntryDTO ep1 = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, 1200L, null, true
        );
        PublicScreenEntryDTO ep2 = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null, 1200L, null, true
        );
        PublicScreenEntryDTO sp1 = new PublicScreenEntryDTO(
                UUID.randomUUID(), "sp-1", ScreenEntryType.SPECIAL, 1, "Ngoại truyện", null, 600L, null, true
        );

        PublicScreenTitleDetailDTO series = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 2, 24, List.of(ep1, ep2, sp1), List.of()
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(series);

        assertThat(view.hasEligibleWatchEntry()).isTrue();
        assertThat(view.hasPlayableWatchEntry()).isTrue();
        assertThat(view.watchActionText()).isEqualTo("Xem ngay");
        assertThat(view.firstEligibleWatchEntry()).isNotNull();
        assertThat(view.firstEligibleWatchEntry().slug()).isEqualTo("tap-1");
        assertThat(view.firstEligibleWatchEntry().number()).isEqualTo(1);
        assertThat(view.watchNowUrl()).isEqualTo("/screen/kiem-lai-1/watch/tap-1");
    }

    @Test
    @DisplayName("Should return Xem ngay for watch action text even when default entry video is not playable")
    void shouldReturnXemNgayWhenDefaultEntryVideoNotPlayable() {
        PublicScreenEntryDTO ep1 = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, 1440L, null, false
        );
        PublicScreenTitleDetailDTO series = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 1, 24, List.of(ep1), List.of()
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(series);

        assertThat(view.hasEligibleWatchEntry()).isTrue();
        assertThat(view.hasPlayableWatchEntry()).isFalse();
        assertThat(view.watchActionText()).isEqualTo("Xem ngay");
        assertThat(view.watchNowUrl()).isEqualTo("/screen/kiem-lai-1/watch/tap-1");
    }

    @Test
    @DisplayName("Should select FEATURE entry for Watch Now action when structure is FEATURE")
    void shouldSelectFeatureEntryForWatchNowWhenStructureIsFeature() {
        PublicScreenEntryDTO feat = new PublicScreenEntryDTO(
                UUID.randomUUID(), "ban-chieu-chinh", ScreenEntryType.FEATURE, null, "Bản Chiếu Chính", null, 7200L, null, true
        );

        PublicScreenTitleDetailDTO feature = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-movie", "Kiếm Lai Điện Ảnh", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(feat), List.of()
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(feature);

        assertThat(view.hasEligibleWatchEntry()).isTrue();
        assertThat(view.hasPlayableWatchEntry()).isTrue();
        assertThat(view.watchActionText()).isEqualTo("Xem ngay");
        assertThat(view.firstEligibleWatchEntry().slug()).isEqualTo("ban-chieu-chinh");
        assertThat(view.watchNowUrl()).isEqualTo("/screen/kiem-lai-movie/watch/ban-chieu-chinh");
    }

    @Test
    @DisplayName("Should return null and false when no eligible watch entry exists")
    void shouldReturnNullWhenNoEligibleWatchEntry() {
        PublicScreenTitleDetailDTO empty = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "empty", "Empty", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), List.of()
        );
        PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(empty);

        assertThat(view.hasEligibleWatchEntry()).isFalse();
        assertThat(view.hasPlayableWatchEntry()).isFalse();
        assertThat(view.firstEligibleWatchEntry()).isNull();
        assertThat(view.watchNowUrl()).isNull();
    }
}
