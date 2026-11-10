package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenWatchDTO;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicScreenWatchView Model Tests")
class PublicScreenWatchViewTest {

    @Test
    @DisplayName("Playable view formats video URLs, headings, and page title accurately")
    void playableViewFormatsCorrectly() {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID thumbnailId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();

        PublicScreenEntryDTO prev = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, 1440L, null, true
        );
        PublicScreenEntryDTO next = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-3", ScreenEntryType.EPISODE, 3, "Tập 3", null, 1500L, null, true
        );
        PublicScreenEntryDTO curr = new PublicScreenEntryDTO(
                entryId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2: Phong Vân", "Tóm tắt", 1440L, thumbnailId, true
        );

        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, entryId, "tap-2", ScreenEntryType.EPISODE, 2,
                "Tập 2: Phong Vân", "Tóm tắt", 1440L, thumbnailId,
                true, "/media/assets/video-123/content", "video/mp4",
                prev, next, List.of(prev, curr, next)
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.hasVideo()).isTrue();
        assertThat(view.videoDeliveryUrl()).isEqualTo("/media/assets/video-123/content");
        assertThat(view.videoMimeType()).isEqualTo("video/mp4");
        assertThat(view.isEpisode()).isTrue();
        assertThat(view.displayHeading()).isEqualTo("Tập 2");
        assertThat(view.nowPlayingHeading()).isEqualTo("Kiếm Lai Phần 1 · Tập 2");
        assertThat(view.typeBadgeText()).isEqualTo("Tập phim");
        assertThat(view.pageTitle()).isEqualTo("Tập 2 - Kiếm Lai Phần 1 - Kiếm Lai Universe");
        assertThat(view.formattedDuration()).isEqualTo("24:00");
        assertThat(view.displayThumbnailUrl()).isEqualTo("/media/assets/" + thumbnailId + "/variants/w600");
        assertThat(view.fallbackThumbnailUrl()).isEqualTo("/media/assets/" + thumbnailId + "/content");
        assertThat(view.displayPosterUrl()).isEqualTo("/media/assets/" + posterId + "/variants/w300");
        assertThat(view.fallbackPosterUrl()).isEqualTo("/media/assets/" + posterId + "/content");
        assertThat(view.hasStageImage()).isTrue();
        assertThat(view.displayStageImageUrl()).isEqualTo("/media/assets/" + thumbnailId + "/variants/w600");
        assertThat(view.fallbackStageImageUrl()).isEqualTo("/media/assets/" + thumbnailId + "/content");
        assertThat(view.hasPrevious()).isTrue();
        assertThat(view.hasNext()).isTrue();
        assertThat(view.previousWatchUrl()).isEqualTo("/screen/kiem-lai-1/watch/tap-1");
        assertThat(view.nextWatchUrl()).isEqualTo("/screen/kiem-lai-1/watch/tap-3");
        assertThat(view.backToTitleUrl()).isEqualTo("/screen/kiem-lai-1");
        assertThat(view.entryDetailUrl()).isEqualTo("/screen/kiem-lai-1/entries/tap-2");
        assertThat(view.isCurrent(entryId)).isTrue();
        assertThat(view.isCurrent(UUID.randomUUID())).isFalse();
        assertThat(view.episodes()).hasSize(3);
    }

    @Test
    @DisplayName("Unplayable view returns false for hasVideo and handles null URLs safely")
    void unplayableViewHandlesNullsSafely() {
        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "kiem-lai-movie", "Kiếm Lai Phim Lẻ",
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, UUID.randomUUID(), "ban-chieu-chinh", ScreenEntryType.FEATURE, null,
                "Bản Chiếu Chính", null, null, null,
                false, null, null,
                null, null, List.of()
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.hasVideo()).isFalse();
        assertThat(view.videoDeliveryUrl()).isNull();
        assertThat(view.videoMimeType()).isNull();
        assertThat(view.isEpisode()).isFalse();
        assertThat(view.displayHeading()).isEqualTo("Bản chiếu chính");
        assertThat(view.nowPlayingHeading()).isEqualTo("Kiếm Lai Phim Lẻ · Bản chiếu chính");
        assertThat(view.typeBadgeText()).isEqualTo("Phim lẻ");
        assertThat(view.hasPrevious()).isFalse();
        assertThat(view.hasNext()).isFalse();
        assertThat(view.previousWatchUrl()).isNull();
        assertThat(view.nextWatchUrl()).isNull();
        assertThat(view.displayThumbnailUrl()).isNull();
        assertThat(view.displayPosterUrl()).isNull();
        assertThat(view.hasStageImage()).isFalse();
        assertThat(view.displayStageImageUrl()).isNull();
        assertThat(view.fallbackStageImageUrl()).isNull();
        assertThat(view.secondaryFallbackStageImageUrl()).isNull();
        assertThat(view.formattedDuration()).isNull();
    }

    @Test
    @DisplayName("Stage image prioritizes episode thumbnail over title backdrop and poster")
    void stageImagePrioritizesThumbnailOverBackdropAndPoster() {
        UUID thumbnailId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();

        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, 1440L, thumbnailId,
                false, null, null,
                null, null, List.of()
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.hasStageImage()).isTrue();
        assertThat(view.isStageImageLandscape()).isTrue();
        assertThat(view.isStageImagePortrait()).isFalse();
        assertThat(view.displayStageImageUrl()).isEqualTo("/media/assets/" + thumbnailId + "/variants/w600");
        assertThat(view.fallbackStageImageUrl()).isEqualTo("/media/assets/" + thumbnailId + "/content");
        assertThat(view.secondaryFallbackStageImageUrl()).isEqualTo("/media/assets/" + backdropId + "/content");
        assertThat(view.displayBackdropUrl()).isEqualTo("/media/assets/" + backdropId + "/variants/w1200");
        assertThat(view.fallbackBackdropUrl()).isEqualTo("/media/assets/" + backdropId + "/content");
    }

    @Test
    @DisplayName("Stage image falls back to title backdrop when entry thumbnail is missing")
    void stageImageFallsBackToBackdropWhenThumbnailMissing() {
        UUID backdropId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();

        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, 1440L, null,
                false, null, null,
                null, null, List.of()
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.thumbnailMediaAssetId()).isNull();
        assertThat(view.backdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(view.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(view.hasStageImage()).isTrue();
        assertThat(view.isStageImageLandscape()).isTrue();
        assertThat(view.isStageImagePortrait()).isFalse();
        assertThat(view.displayStageImageUrl()).isEqualTo("/media/assets/" + backdropId + "/variants/w1200");
        assertThat(view.fallbackStageImageUrl()).isEqualTo("/media/assets/" + backdropId + "/content");
        assertThat(view.secondaryFallbackStageImageUrl()).isEqualTo("/media/assets/" + posterId + "/content");
    }

    @Test
    @DisplayName("Stage image falls back to title poster when entry thumbnail and backdrop are both missing")
    void stageImageFallsBackToPosterWhenThumbnailMissing() {
        UUID posterId = UUID.randomUUID();
        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, 1440L, null,
                false, null, null,
                null, null, List.of()
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.thumbnailMediaAssetId()).isNull();
        assertThat(view.backdropMediaAssetId()).isNull();
        assertThat(view.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(view.hasStageImage()).isTrue();
        assertThat(view.isStageImagePortrait()).isTrue();
        assertThat(view.isStageImageLandscape()).isFalse();
        assertThat(view.displayStageImageUrl()).isEqualTo("/media/assets/" + posterId + "/variants/w600");
        assertThat(view.fallbackStageImageUrl()).isEqualTo("/media/assets/" + posterId + "/content");
        assertThat(view.secondaryFallbackStageImageUrl()).isNull();
    }

    @Test
    @DisplayName("Duration formatting handles hours correctly")
    void durationFormattingWithHours() {
        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "movie", "Movie",
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION,
                null, UUID.randomUUID(), "main", ScreenEntryType.FEATURE, null,
                "Feature", null, 7325L, null,
                true, "/media/assets/vid/content", "video/mp4",
                null, null, List.of()
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.formattedDuration()).isEqualTo("2:02:05");
    }

    @Test
    @DisplayName("UX02-C2.1: Bounded Outro formatting on PublicScreenWatchView [24:12 -> 25:30]")
    void boundedOutroFormattingOnWatchView() {
        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, 1576L, null,
                true, "/media/assets/vid/content", "video/mp4",
                null, null, List.of(),
                0L, 90L, true,
                1452L, 1530L, true
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.hasEffectiveIntro()).isTrue();
        assertThat(view.effectiveIntroStartSeconds()).isEqualTo(0L);
        assertThat(view.effectiveIntroEndSeconds()).isEqualTo(90L);
        assertThat(view.hasEffectiveOutro()).isTrue();
        assertThat(view.hasBoundedEffectiveOutro()).isTrue();
        assertThat(view.effectiveOutroStartSeconds()).isEqualTo(1452L);
        assertThat(view.effectiveOutroEndSeconds()).isEqualTo(1530L);
        assertThat(view.formattedEffectiveOutroStart()).isEqualTo("24:12");
        assertThat(view.formattedEffectiveOutroEnd()).isEqualTo("25:30");
    }

    @Test
    @DisplayName("UX02-C2.1: Unbounded Outro formatting on PublicScreenWatchView [24:12 -> null]")
    void unboundedOutroFormattingOnWatchView() {
        PublicScreenWatchDTO dto = new PublicScreenWatchDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, 1576L, null,
                true, "/media/assets/vid/content", "video/mp4",
                null, null, List.of(),
                null, null, false,
                1452L, null, true
        );

        PublicScreenWatchView view = PublicScreenWatchView.fromDTO(dto);

        assertThat(view.hasEffectiveIntro()).isFalse();
        assertThat(view.hasEffectiveOutro()).isTrue();
        assertThat(view.hasBoundedEffectiveOutro()).isFalse();
        assertThat(view.effectiveOutroStartSeconds()).isEqualTo(1452L);
        assertThat(view.effectiveOutroEndSeconds()).isNull();
        assertThat(view.formattedEffectiveOutroStart()).isEqualTo("24:12");
        assertThat(view.formattedEffectiveOutroEnd()).isNull();
    }
}
