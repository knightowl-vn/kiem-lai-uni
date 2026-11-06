package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenWatchDTO;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.ports.ScreenVideoDeliveryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicScreenWatchUseCase Tests")
class GetPublicScreenWatchUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepositoryPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    @Mock
    private ScreenVideoDeliveryPort videoDeliveryPort;

    private GetPublicScreenWatchUseCase useCase;

    private final Instant now = Instant.parse("2026-11-05T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetPublicScreenWatchUseCase(
                titleRepositoryPort,
                entryRepositoryPort,
                videoDeliveryPort
        );
    }

    private ScreenTitle createPublishedTitle(UUID titleId, String slug, String title, ScreenStructure structure) {
        return new ScreenTitle(
                titleId, slug, title, null, "Synopsis",
                structure, ScreenPresentation.ANIMATION_3D,
                UUID.randomUUID(), UUID.randomUUID(),
                ScreenPublicationStatus.PUBLISHED, now, now, now,
                structure == ScreenStructure.FEATURE ? null : 12, null, 2026, "VN", null, null, null
        );
    }

    private ScreenEntry createPublishedEntry(
            UUID entryId, UUID titleId, String slug, ScreenEntryType type,
            Integer number, String title, UUID videoAssetId
    ) {
        return new ScreenEntry(
                entryId, titleId, slug, type, number, title, "Synopsis",
                videoAssetId, UUID.randomUUID(), 1440L,
                ScreenPublicationStatus.PUBLISHED, now, now, now
        );
    }

    @Nested
    @DisplayName("Title and Entry Resolution & Privacy Tests")
    class ResolutionAndPrivacyTests {

        @Test
        @DisplayName("Valid published SERIES title and EPISODE entry with playable video resolves successfully")
        void validPublishedSeriesAndEpisodeResolves() {
            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();
            UUID videoAssetId = UUID.randomUUID();

            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai Phần 1", ScreenStructure.SERIES);
            ScreenEntry entry = createPublishedEntry(entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1: Xuất Thế", videoAssetId);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(entry));
            when(videoDeliveryPort.resolvePlaybackDelivery(videoAssetId))
                    .thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(videoAssetId, "/media/assets/" + videoAssetId + "/content", "video/mp4")));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(entry));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(entry));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-1");

            assertThat(result).isNotNull();
            assertThat(result.titleId()).isEqualTo(titleId);
            assertThat(result.titleSlug()).isEqualTo("kiem-lai-1");
            assertThat(result.titleDisplayName()).isEqualTo("Kiếm Lai Phần 1");
            assertThat(result.titleStructure()).isEqualTo(ScreenStructure.SERIES);
            assertThat(result.posterMediaAssetId()).isEqualTo(title.getPosterMediaAssetId());
            assertThat(result.backdropMediaAssetId()).isEqualTo(title.getBackdropMediaAssetId());
            assertThat(result.entryId()).isEqualTo(entryId);
            assertThat(result.isPlayable()).isTrue();
            assertThat(result.videoDeliveryUrl()).isEqualTo("/media/assets/" + videoAssetId + "/content");
            assertThat(result.videoMimeType()).isEqualTo("video/mp4");
            assertThat(result.entrySlug()).isEqualTo("tap-1");
            assertThat(result.entryType()).isEqualTo(ScreenEntryType.EPISODE);
            assertThat(result.entryNumber()).isEqualTo(1);
            assertThat(result.entryTitle()).isEqualTo("Tập 1: Xuất Thế");
            assertThat(result.isPlayable()).isTrue();
            assertThat(result.videoDeliveryUrl()).isEqualTo("/media/assets/" + videoAssetId + "/content");
            assertThat(result.previousEntry()).isNull();
            assertThat(result.nextEntry()).isNull();
            assertThat(result.playlist()).hasSize(1);
        }

        @Test
        @DisplayName("Entry without video attachment resolves as unplayable without leaking URLs")
        void entryWithoutVideoResolvesAsUnplayable() {
            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();

            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai Phần 1", ScreenStructure.SERIES);
            ScreenEntry entry = createPublishedEntry(entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(entry));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(entry));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(entry));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-1");

            assertThat(result.isPlayable()).isFalse();
            assertThat(result.videoDeliveryUrl()).isNull();
            verifyNoInteractions(videoDeliveryPort);
        }

        @Test
        @DisplayName("Entry with unplayable video reference (e.g. deleted/archived/private) resolves with isPlayable=false")
        void entryWithUnplayableVideoReferenceResolvesSafely() {
            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();
            UUID videoAssetId = UUID.randomUUID();

            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai Phần 1", ScreenStructure.SERIES);
            ScreenEntry entry = createPublishedEntry(entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", videoAssetId);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(entry));
            when(videoDeliveryPort.resolvePlaybackDelivery(videoAssetId)).thenReturn(Optional.empty());
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(entry));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(entry));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-1");

            assertThat(result.isPlayable()).isFalse();
            assertThat(result.videoDeliveryUrl()).isNull();
        }

        @Test
        @DisplayName("Unknown title slug throws PublishedScreenTitleNotFoundException")
        void unknownTitleSlugThrowsNotFound() {
            when(titleRepositoryPort.findPublishedBySlug("unknown-title")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute("unknown-title", "tap-1"))
                    .isInstanceOf(PublishedScreenTitleNotFoundException.class)
                    .hasMessageContaining("unknown-title");
        }

        @Test
        @DisplayName("DRAFT or ARCHIVED title slug throws PublishedScreenTitleNotFoundException (never exposed)")
        void draftOrArchivedTitleThrowsNotFound() {
            when(titleRepositoryPort.findPublishedBySlug("draft-title")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute("draft-title", "tap-1"))
                    .isInstanceOf(PublishedScreenTitleNotFoundException.class);
        }

        @Test
        @DisplayName("Null or blank title slug throws PublishedScreenTitleNotFoundException")
        void nullOrBlankTitleSlugThrowsNotFound() {
            assertThatThrownBy(() -> useCase.execute(null, "tap-1"))
                    .isInstanceOf(PublishedScreenTitleNotFoundException.class);
            assertThatThrownBy(() -> useCase.execute("   ", "tap-1"))
                    .isInstanceOf(PublishedScreenTitleNotFoundException.class);
        }

        @Test
        @DisplayName("Unknown entry slug under valid title throws PublishedScreenEntryNotFoundException")
        void unknownEntrySlugThrowsNotFound() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute("kiem-lai-1", "tap-999"))
                    .isInstanceOf(PublishedScreenEntryNotFoundException.class)
                    .hasMessageContaining("tap-999");
        }

        @Test
        @DisplayName("DRAFT or ARCHIVED entry slug under valid title throws PublishedScreenEntryNotFoundException")
        void draftOrArchivedEntryThrowsNotFound() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-draft")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute("kiem-lai-1", "tap-draft"))
                    .isInstanceOf(PublishedScreenEntryNotFoundException.class);
        }

        @Test
        @DisplayName("Cross-title entry rejected: entry belonging to title B requested via title A throws PublishedScreenEntryNotFoundException")
        void crossTitleEntryResolutionRejectedWithoutLeakage() {
            UUID titleAId = UUID.randomUUID();
            ScreenTitle titleA = createPublishedTitle(titleAId, "title-a", "Title A", ScreenStructure.SERIES);

            when(titleRepositoryPort.findPublishedBySlug("title-a")).thenReturn(Optional.of(titleA));
            // Entry belongs to title B, so findPublishedByTitleIdAndSlug with titleAId returns empty
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleAId, "entry-b-slug")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.execute("title-a", "entry-b-slug"))
                    .isInstanceOf(PublishedScreenEntryNotFoundException.class)
                    .hasMessageContaining("entry-b-slug");
        }
    }

    @Nested
    @DisplayName("Deterministic Navigation & Ordering Tests")
    class NavigationAndOrderingTests {

        @Test
        @DisplayName("SERIES navigation: First episode has previousEntry=null and nextEntry=episode2")
        void seriesFirstEpisodeNavigation() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            ScreenEntry ep1 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null);
            ScreenEntry ep2 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null);
            ScreenEntry ep3 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-3", ScreenEntryType.EPISODE, 3, "Tập 3", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(ep1));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1, ep2, ep3));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(ep1, ep2, ep3));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-1");

            assertThat(result.previousEntry()).isNull();
            assertThat(result.nextEntry()).isNotNull();
            assertThat(result.nextEntry().slug()).isEqualTo("tap-2");
            assertThat(result.nextEntry().number()).isEqualTo(2);
        }

        @Test
        @DisplayName("SERIES navigation: Middle episode has valid previousEntry and nextEntry")
        void seriesMiddleEpisodeNavigation() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            ScreenEntry ep1 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null);
            ScreenEntry ep2 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null);
            ScreenEntry ep3 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-3", ScreenEntryType.EPISODE, 3, "Tập 3", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-2")).thenReturn(Optional.of(ep2));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1, ep2, ep3));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(ep1, ep2, ep3));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-2");

            assertThat(result.previousEntry()).isNotNull();
            assertThat(result.previousEntry().slug()).isEqualTo("tap-1");
            assertThat(result.previousEntry().number()).isEqualTo(1);
            assertThat(result.nextEntry()).isNotNull();
            assertThat(result.nextEntry().slug()).isEqualTo("tap-3");
            assertThat(result.nextEntry().number()).isEqualTo(3);
        }

        @Test
        @DisplayName("SERIES navigation: Last episode has valid previousEntry and nextEntry=null")
        void seriesLastEpisodeNavigation() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            ScreenEntry ep1 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null);
            ScreenEntry ep2 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-2")).thenReturn(Optional.of(ep2));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1, ep2));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(ep1, ep2));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-2");

            assertThat(result.previousEntry()).isNotNull();
            assertThat(result.previousEntry().slug()).isEqualTo("tap-1");
            assertThat(result.nextEntry()).isNull();
        }

        @Test
        @DisplayName("SERIES navigation skips unpublished (DRAFT/ARCHIVED) episodes naturally")
        void seriesNavigationSkipsUnpublishedEpisodes() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            // Suppose Episode 2 was DRAFT, so findPublishedEpisodesByTitleId returns only Episode 1 and Episode 3
            ScreenEntry ep1 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null);
            ScreenEntry ep3 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-3", ScreenEntryType.EPISODE, 3, "Tập 3", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(ep1));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1, ep3));
            when(entryRepositoryPort.findPublishedEpisodesByTitleId(titleId)).thenReturn(List.of(ep1, ep3));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-1");

            assertThat(result.previousEntry()).isNull();
            assertThat(result.nextEntry()).isNotNull();
            assertThat(result.nextEntry().slug()).isEqualTo("tap-3");
            assertThat(result.nextEntry().number()).isEqualTo(3);
        }

        @Test
        @DisplayName("SPECIAL entries in a SERIES title do not participate in previous/next episode navigation")
        void specialEntryOmitsEpisodeNavigation() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            ScreenEntry ep1 = createPublishedEntry(UUID.randomUUID(), titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null);
            ScreenEntry sp1 = createPublishedEntry(UUID.randomUUID(), titleId, "ngoai-truyen-1", ScreenEntryType.SPECIAL, 1, "Ngoại truyện 1", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "ngoai-truyen-1")).thenReturn(Optional.of(sp1));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1, sp1));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "ngoai-truyen-1");

            assertThat(result.previousEntry()).isNull();
            assertThat(result.nextEntry()).isNull();
            assertThat(result.playlist()).hasSize(2);
        }

        @Test
        @DisplayName("TRAILER entries in a SERIES title do not participate in previous/next episode navigation")
        void trailerEntryOmitsEpisodeNavigation() {
            UUID titleId = UUID.randomUUID();
            ScreenTitle title = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);

            ScreenEntry tr1 = createPublishedEntry(UUID.randomUUID(), titleId, "trailer-1", ScreenEntryType.TRAILER, null, "Trailer 1", null);

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "trailer-1")).thenReturn(Optional.of(tr1));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(tr1));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "trailer-1");

            assertThat(result.previousEntry()).isNull();
            assertThat(result.nextEntry()).isNull();
        }
    }

    @Nested
    @DisplayName("FEATURE, SPECIAL, and TRAILER Behavior Tests")
    class StructureAndTypeTests {

        @Test
        @DisplayName("FEATURE entry resolves with null entryNumber and no fabricated episode or episode navigation")
        void featureEntryResolvesWithNullNumberAndNoEpisodeNavigation() {
            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();
            UUID videoAssetId = UUID.randomUUID();

            ScreenTitle movie = createPublishedTitle(titleId, "kiem-lai-movie", "Kiếm Lai Điện Ảnh", ScreenStructure.FEATURE);
            ScreenEntry featEntry = new ScreenEntry(
                    entryId, titleId, "ban-chieu-chinh", ScreenEntryType.FEATURE, null,
                    "Bản Chiếu Chính", "Phim điện ảnh", videoAssetId, null, 7200L,
                    ScreenPublicationStatus.PUBLISHED, now, now, now
            );

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-movie")).thenReturn(Optional.of(movie));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "ban-chieu-chinh")).thenReturn(Optional.of(featEntry));
            when(videoDeliveryPort.resolvePlaybackDelivery(videoAssetId))
                    .thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(videoAssetId, "/media/assets/" + videoAssetId + "/content", "video/mp4")));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(featEntry));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-movie", "ban-chieu-chinh");

            assertThat(result).isNotNull();
            assertThat(result.entryNumber()).isNull();
            assertThat(result.entryType()).isEqualTo(ScreenEntryType.FEATURE);
            assertThat(result.previousEntry()).isNull();
            assertThat(result.nextEntry()).isNull();
            assertThat(result.isPlayable()).isTrue();
        }

        @Test
        @DisplayName("SPECIAL entry with null number resolves through its slug cleanly")
        void specialEntryWithNullNumberResolvesThroughSlug() {
            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();

            ScreenTitle series = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);
            ScreenEntry special = new ScreenEntry(
                    entryId, titleId, "tap-dac-biet", ScreenEntryType.SPECIAL, null,
                    "Tập Đặc Biệt", null, null, null, null,
                    ScreenPublicationStatus.PUBLISHED, now, now, now
            );

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(series));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-dac-biet")).thenReturn(Optional.of(special));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(special));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "tap-dac-biet");

            assertThat(result.entrySlug()).isEqualTo("tap-dac-biet");
            assertThat(result.entryNumber()).isNull();
            assertThat(result.entryType()).isEqualTo(ScreenEntryType.SPECIAL);
        }

        @Test
        @DisplayName("TRAILER entry with null number resolves cleanly")
        void trailerEntryWithNullNumberResolves() {
            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();

            ScreenTitle series = createPublishedTitle(titleId, "kiem-lai-1", "Kiếm Lai", ScreenStructure.SERIES);
            ScreenEntry trailer = new ScreenEntry(
                    entryId, titleId, "trailer-teaser", ScreenEntryType.TRAILER, null,
                    "Teaser Trailer", null, null, null, null,
                    ScreenPublicationStatus.PUBLISHED, now, now, now
            );

            when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(series));
            when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "trailer-teaser")).thenReturn(Optional.of(trailer));
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(trailer));

            PublicScreenWatchDTO result = useCase.execute("kiem-lai-1", "trailer-teaser");

            assertThat(result.entrySlug()).isEqualTo("trailer-teaser");
            assertThat(result.entryNumber()).isNull();
            assertThat(result.entryType()).isEqualTo(ScreenEntryType.TRAILER);
        }
    }
}
