package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.ports.ScreenVideoDeliveryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenFranchise;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicScreenTitleDetailUseCase Unit Tests")
class GetPublicScreenTitleDetailUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepositoryPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    @Mock
    private ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepositoryPort;

    @Mock
    private ScreenFranchiseRepositoryPort franchiseRepositoryPort;

    @Mock
    private ScreenVideoDeliveryPort videoDeliveryPort;

    private GetPublicScreenTitleDetailUseCase useCase;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetPublicScreenTitleDetailUseCase(
                titleRepositoryPort,
                entryRepositoryPort,
                franchiseMembershipRepositoryPort,
                franchiseRepositoryPort,
                videoDeliveryPort
        );
    }

    @Test
    @DisplayName("Should retrieve published SERIES title detail with entries and franchise navigation")
    void shouldRetrievePublishedSeriesTitleDetailSuccessfully() {
        UUID titleId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-donghua-1", "Kiếm Lai Phần 1", "剑来 第一季",
                "Hành trình của Trần Bình An", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-donghua-1")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(12);

        // Entries inserted in arbitrary order to verify sorting: Episode 2, Special 1, Episode 1
        UUID ep1Id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID ep2Id = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID sp1Id = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID ep1VideoId = UUID.randomUUID();
        UUID ep2VideoId = UUID.randomUUID();

        ScreenEntry ep2 = new ScreenEntry(ep2Id, titleId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null, ep2VideoId, null, 1440L, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry sp1 = new ScreenEntry(sp1Id, titleId, "sp-1", ScreenEntryType.SPECIAL, 1, "Ngoại truyện 1", null, null, null, 600L, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry ep1 = new ScreenEntry(ep1Id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, ep1VideoId, null, 1440L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep2, sp1, ep1));
        when(videoDeliveryPort.resolvePlaybackDelivery(ep1VideoId)).thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(ep1VideoId, "/media/assets/" + ep1VideoId + "/content", "video/mp4")));

        // Franchise setup
        UUID franchiseId = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(franchiseId, "kiem-lai", "Kiếm Lai");
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, titleId, 0);

        UUID siblingId = UUID.randomUUID();
        ScreenTitle siblingTitle = new ScreenTitle(
                siblingId, "kiem-lai-donghua-2", "Kiếm Lai Phần 2", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
        );

        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(membership));
        when(franchiseRepositoryPort.findById(franchiseId)).thenReturn(Optional.of(franchise));
        when(titleRepositoryPort.findPublishedByFranchiseId(franchiseId)).thenReturn(List.of(title, siblingTitle));

        PublicScreenTitleDetailDTO result = useCase.execute("kiem-lai-donghua-1");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(titleId);
        assertThat(result.slug()).isEqualTo("kiem-lai-donghua-1");
        assertThat(result.title()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(result.originalTitle()).isEqualTo("剑来 第一季");
        assertThat(result.synopsis()).isEqualTo("Hành trình của Trần Bình An");
        assertThat(result.structure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(result.presentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(result.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(result.backdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(result.latestPublishedEpisodeNumber()).isEqualTo(12);
        assertThat(result.totalEpisodeCount()).isEqualTo(24);

        // Verify entry deterministic ordering: ep1 -> ep2 -> sp1
        assertThat(result.entries()).hasSize(3);
        assertThat(result.entries().get(0).id()).isEqualTo(ep1Id);
        assertThat(result.entries().get(0).number()).isEqualTo(1);
        assertThat(result.entries().get(0).hasPlayableVideo()).isTrue();
        verify(videoDeliveryPort).resolvePlaybackDelivery(ep1VideoId);
        verify(videoDeliveryPort, never()).resolvePlaybackDelivery(ep2VideoId);
        assertThat(result.entries().get(1).id()).isEqualTo(ep2Id);
        assertThat(result.entries().get(1).number()).isEqualTo(2);
        assertThat(result.entries().get(2).id()).isEqualTo(sp1Id);
        assertThat(result.entries().get(2).type()).isEqualTo(ScreenEntryType.SPECIAL);
        assertThat(result.entries().get(2).hasPlayableVideo()).isFalse();

        // Verify franchise navigation
        PublicScreenFranchiseDTO franchiseDTO = result.franchise();
        assertThat(franchiseDTO).isNotNull();
        assertThat(franchiseDTO.id()).isEqualTo(franchiseId);
        assertThat(franchiseDTO.name()).isEqualTo("Kiếm Lai");
        assertThat(franchiseDTO.installments()).hasSize(2);
        assertThat(franchiseDTO.installments().get(0).id()).isEqualTo(titleId);
        assertThat(franchiseDTO.installments().get(0).isCurrent()).isTrue();
        assertThat(franchiseDTO.installments().get(1).id()).isEqualTo(siblingId);
        assertThat(franchiseDTO.installments().get(1).isCurrent()).isFalse();
    }

    @Test
    @DisplayName("Should retrieve published FEATURE title without querying episode number")
    void shouldRetrievePublishedFeatureTitleDetailSuccessfully() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle movieTitle = new ScreenTitle(
                titleId, "kiem-lai-movie-1", "Kiếm Lai Điện Ảnh 1", null,
                null, ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, null
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-movie-1")).thenReturn(Optional.of(movieTitle));

        UUID featEntryId = UUID.randomUUID();
        UUID trailerEntryId = UUID.randomUUID();
        UUID featVideoId = UUID.randomUUID();
        UUID trailerVideoId = UUID.randomUUID();

        ScreenEntry featEntry = new ScreenEntry(featEntryId, titleId, "main-feature", ScreenEntryType.FEATURE, null, "Bản chiếu rạp", null, featVideoId, null, 7200L, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry trailerEntry = new ScreenEntry(trailerEntryId, titleId, "trailer-1", ScreenEntryType.TRAILER, 1, "Trailer 1", null, trailerVideoId, null, 120L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(trailerEntry, featEntry));
        when(videoDeliveryPort.resolvePlaybackDelivery(featVideoId)).thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(featVideoId, "/media/assets/" + featVideoId + "/content", "video/mp4")));
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());

        PublicScreenTitleDetailDTO result = useCase.execute("kiem-lai-movie-1");

        assertThat(result).isNotNull();
        assertThat(result.structure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(result.latestPublishedEpisodeNumber()).isNull();
        verify(entryRepositoryPort, never()).findLatestPublishedEpisodeNumberByTitleId(any());

        // FEATURE entry (type order 1) comes before TRAILER (type order 3)
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).id()).isEqualTo(featEntryId);
        assertThat(result.entries().get(0).type()).isEqualTo(ScreenEntryType.FEATURE);
        assertThat(result.entries().get(0).number()).isNull();
        assertThat(result.entries().get(0).hasPlayableVideo()).isTrue();
        verify(videoDeliveryPort).resolvePlaybackDelivery(featVideoId);
        verify(videoDeliveryPort, never()).resolvePlaybackDelivery(trailerVideoId);
        assertThat(result.entries().get(1).id()).isEqualTo(trailerEntryId);
        assertThat(result.entries().get(1).type()).isEqualTo(ScreenEntryType.TRAILER);

        assertThat(result.franchise()).isNull();
    }

    @Test
    @DisplayName("Should throw PublishedScreenTitleNotFoundException when title slug is not found")
    void shouldThrowNotFoundWhenTitleSlugNotFound() {
        when(titleRepositoryPort.findPublishedBySlug("unknown-slug")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("unknown-slug"))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class)
                .hasMessageContaining("unknown-slug");
    }

    @Test
    @DisplayName("Should throw PublishedScreenTitleNotFoundException when slug is null or blank")
    void shouldThrowNotFoundWhenSlugIsNullOrBlank() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);

        assertThatThrownBy(() -> useCase.execute("   "))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);
    }

    @Test
    @DisplayName("Should normalize slug before querying title repository")
    void shouldNormalizeSlugBeforeQuerying() {
        ScreenTitle title = new ScreenTitle(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai 1", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, null
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(title.getId())).thenReturn(List.of());
        when(franchiseMembershipRepositoryPort.findByTitleId(title.getId())).thenReturn(List.of());

        PublicScreenTitleDetailDTO result = useCase.execute("  KIEM-LAI-1  ");

        assertThat(result).isNotNull();
        verify(titleRepositoryPort).findPublishedBySlug("kiem-lai-1");
    }

    @Test
    @DisplayName("Should return valid detail DTO when published title has zero published entries")
    void shouldReturnValidDetailWhenZeroPublishedEntries() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-empty", "Kiếm Lai Chưa Chiếu", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 12
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-empty")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(null);
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of());
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());

        PublicScreenTitleDetailDTO result = useCase.execute("kiem-lai-empty");

        assertThat(result).isNotNull();
        assertThat(result.entries()).isEmpty();
        assertThat(result.latestPublishedEpisodeNumber()).isNull();
    }

    @Test
    @DisplayName("Should resolve franchise cleanly when only the current title is published")
    void shouldResolveFranchiseWhenOnlyCurrentTitleIsPublished() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-solo", "Kiếm Lai Độc Lập", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, null
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-solo")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of());

        UUID franchiseId = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(franchiseId, "kiem-lai-univ", "Kiếm Lai Universe");
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, titleId, 0);

        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(membership));
        when(franchiseRepositoryPort.findById(franchiseId)).thenReturn(Optional.of(franchise));
        when(titleRepositoryPort.findPublishedByFranchiseId(franchiseId)).thenReturn(List.of(title));

        PublicScreenTitleDetailDTO result = useCase.execute("kiem-lai-solo");

        assertThat(result.franchise()).isNotNull();
        assertThat(result.franchise().installments()).hasSize(1);
        assertThat(result.franchise().installments().get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("Should use ID ASC tie-breaker for entries with identical episode numbers")
    void shouldSortEntriesWithIdenticalNumbersByIdAscTieBreaker() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "tie-break-slug", "Tie Break", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, null
        );

        when(titleRepositoryPort.findPublishedBySlug("tie-break-slug")).thenReturn(Optional.of(title));

        UUID epLowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID epHighId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        ScreenEntry epHigh = new ScreenEntry(epHighId, titleId, "ep-high", ScreenEntryType.EPISODE, 1, "Ep High", null, null, null, null, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry epLow = new ScreenEntry(epLowId, titleId, "ep-low", ScreenEntryType.EPISODE, 1, "Ep Low", null, null, null, null, ScreenPublicationStatus.PUBLISHED, now, now, now);

        // High inserted before Low
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(epHigh, epLow));
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());

        PublicScreenTitleDetailDTO result = useCase.execute("tie-break-slug");

        assertThat(result.entries()).extracting(PublicScreenEntryDTO::id).containsExactly(epLowId, epHighId);
    }

    @Test
    @DisplayName("Should resolve multiple franchise groups when title belongs to multiple franchises")
    void shouldResolveMultipleFranchisesWhenTitleBelongsToMultipleFranchises() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "multi-fran-title", "Multi Franchise Title", null,
                null, ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, null
        );

        when(titleRepositoryPort.findPublishedBySlug("multi-fran-title")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of());

        UUID franchise1Id = UUID.randomUUID();
        UUID franchise2Id = UUID.randomUUID();

        ScreenFranchise franchise1 = new ScreenFranchise(franchise1Id, "main-universe", "Vũ Trụ Chính");
        ScreenFranchise franchise2 = new ScreenFranchise(franchise2Id, "spin-off-universe", "Ngoại Truyện Tuyển Tập");

        ScreenFranchiseMembership mem1 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise1Id, titleId, 0);
        ScreenFranchiseMembership mem2 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise2Id, titleId, 1);

        UUID sibling1Id = UUID.randomUUID();
        ScreenTitle sibling1 = new ScreenTitle(
                sibling1Id, "sibling-1", "Phần Tiền Truyện", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 12
        );

        UUID sibling2Id = UUID.randomUUID();
        ScreenTitle sibling2 = new ScreenTitle(
                sibling2Id, "sibling-2", "Tuyển Tập Đặc Biệt", null,
                null, ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, null
        );

        // Memberships returned in order from repository
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(mem1, mem2));
        when(franchiseRepositoryPort.findById(franchise1Id)).thenReturn(Optional.of(franchise1));
        when(franchiseRepositoryPort.findById(franchise2Id)).thenReturn(Optional.of(franchise2));

        when(titleRepositoryPort.findPublishedByFranchiseId(franchise1Id)).thenReturn(List.of(sibling1, title));
        when(titleRepositoryPort.findPublishedByFranchiseId(franchise2Id)).thenReturn(List.of(title, sibling2));

        PublicScreenTitleDetailDTO result = useCase.execute("multi-fran-title");

        assertThat(result.franchises()).hasSize(2);

        // Franchise 1
        PublicScreenFranchiseDTO f1 = result.franchises().get(0);
        assertThat(f1.id()).isEqualTo(franchise1Id);
        assertThat(f1.name()).isEqualTo("Vũ Trụ Chính");
        assertThat(f1.installments()).hasSize(2);
        assertThat(f1.installments().get(0).id()).isEqualTo(sibling1Id);
        assertThat(f1.installments().get(0).isCurrent()).isFalse();
        assertThat(f1.installments().get(1).id()).isEqualTo(titleId);
        assertThat(f1.installments().get(1).isCurrent()).isTrue();

        // Franchise 2
        PublicScreenFranchiseDTO f2 = result.franchises().get(1);
        assertThat(f2.id()).isEqualTo(franchise2Id);
        assertThat(f2.name()).isEqualTo("Ngoại Truyện Tuyển Tập");
        assertThat(f2.installments()).hasSize(2);
        assertThat(f2.installments().get(0).id()).isEqualTo(titleId);
        assertThat(f2.installments().get(0).isCurrent()).isTrue();
        assertThat(f2.installments().get(1).id()).isEqualTo(sibling2Id);
        assertThat(f2.installments().get(1).isCurrent()).isFalse();

        // Backward compatibility: result.franchise() returns first franchise
        assertThat(result.franchise()).isEqualTo(f1);
    }

    @Test
    @DisplayName("Should correctly compute average duration for SERIES and FEATURE")
    void shouldComputeAverageDurationCorrectly() {
        UUID titleId = UUID.randomUUID();

        // SERIES with 2 episodes: 1440s (24 min) and 1500s (25 min) -> avg 1470s -> 25 min
        // Also includes a special (600s) which must be excluded!
        ScreenEntry ep1 = new ScreenEntry(UUID.randomUUID(), titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1440L, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry ep2 = new ScreenEntry(UUID.randomUUID(), titleId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null, null, null, 1500L, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry sp = new ScreenEntry(UUID.randomUUID(), titleId, "sp-1", ScreenEntryType.SPECIAL, 1, "Ngoại truyện", null, null, null, 600L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        Integer seriesDuration = GetPublicScreenTitleDetailUseCase.calculateAverageDurationMinutes(ScreenStructure.SERIES, List.of(ep1, ep2, sp));
        assertThat(seriesDuration).isEqualTo(25);

        // FEATURE with 7200s (120 min), trailer 120s
        ScreenEntry feat = new ScreenEntry(UUID.randomUUID(), titleId, "movie", ScreenEntryType.FEATURE, null, "Bản rạp", null, null, null, 7200L, ScreenPublicationStatus.PUBLISHED, now, now, now);
        ScreenEntry tr = new ScreenEntry(UUID.randomUUID(), titleId, "tr", ScreenEntryType.TRAILER, 1, "Trailer", null, null, null, 120L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        Integer featDuration = GetPublicScreenTitleDetailUseCase.calculateAverageDurationMinutes(ScreenStructure.FEATURE, List.of(feat, tr));
        assertThat(featDuration).isEqualTo(120);

        // Edge cases: null or empty
        assertThat(GetPublicScreenTitleDetailUseCase.calculateAverageDurationMinutes(null, List.of(ep1))).isNull();
        assertThat(GetPublicScreenTitleDetailUseCase.calculateAverageDurationMinutes(ScreenStructure.SERIES, null)).isNull();
        assertThat(GetPublicScreenTitleDetailUseCase.calculateAverageDurationMinutes(ScreenStructure.SERIES, List.of())).isNull();
        assertThat(GetPublicScreenTitleDetailUseCase.calculateAverageDurationMinutes(ScreenStructure.SERIES, List.of(sp))).isNull();
    }

    @Test
    @DisplayName("Should map all extended metadata fields to PublicScreenTitleDetailDTO")
    void shouldMapExtendedMetadataFieldsToDTO() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "meta-slug", "Title Meta", "Original Title",
                "Synopsis text", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now,
                24, com.universe.screen.domain.ScreenAiringStatus.ONGOING, 2024,
                "Trung Quốc", "Tác giả gốc", "Đạo diễn A", "Studio B"
        );

        when(titleRepositoryPort.findPublishedBySlug("meta-slug")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of());
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());

        PublicScreenTitleDetailDTO result = useCase.execute("meta-slug");

        assertThat(result.airingStatus()).isEqualTo(com.universe.screen.domain.ScreenAiringStatus.ONGOING);
        assertThat(result.releaseYear()).isEqualTo(2024);
        assertThat(result.country()).isEqualTo("Trung Quốc");
        assertThat(result.originalAuthor()).isEqualTo("Tác giả gốc");
        assertThat(result.director()).isEqualTo("Đạo diễn A");
        assertThat(result.productionStudio()).isEqualTo("Studio B");
        assertThat(result.averageDurationMinutes()).isNull();
    }

    @Test
    @DisplayName("Should mark default entry playable when authoritative video delivery info is active and available")
    void shouldMarkDefaultEntryPlayableWhenAuthoritativeVideoIsActiveAndAvailable() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "series-playable", "Series Playable", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 12
        );

        UUID epId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        ScreenEntry episode1 = new ScreenEntry(epId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, videoId, null, 1200L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        when(titleRepositoryPort.findPublishedBySlug("series-playable")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(episode1));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(1);
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
        when(videoDeliveryPort.resolvePlaybackDelivery(videoId)).thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(videoId, "/media/assets/" + videoId + "/content", "video/mp4")));

        PublicScreenTitleDetailDTO result = useCase.execute("series-playable");

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).hasPlayableVideo()).isTrue();
        verify(videoDeliveryPort).resolvePlaybackDelivery(videoId);
    }

    @Test
    @DisplayName("Should mark default entry unplayable when attached video Media asset is not playable")
    void shouldMarkDefaultEntryUnplayableWhenAttachedVideoMediaAssetIsNotPlayable() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "series-unplayable", "Series Unplayable", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 12
        );

        UUID epId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        ScreenEntry episode1 = new ScreenEntry(epId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, videoId, null, 1200L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        when(titleRepositoryPort.findPublishedBySlug("series-unplayable")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(episode1));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(1);
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
        when(videoDeliveryPort.resolvePlaybackDelivery(videoId)).thenReturn(Optional.empty());

        PublicScreenTitleDetailDTO result = useCase.execute("series-unplayable");

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).hasPlayableVideo()).isFalse();
        verify(videoDeliveryPort).resolvePlaybackDelivery(videoId);
    }

    @Test
    @DisplayName("Should mark default entry unplayable when no video Media asset is attached without calling delivery port")
    void shouldMarkDefaultEntryUnplayableWhenNoVideoMediaAssetIsAttached() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "series-no-video", "Series No Video", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 12
        );

        UUID epId = UUID.randomUUID();
        ScreenEntry episode1 = new ScreenEntry(epId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1200L, ScreenPublicationStatus.PUBLISHED, now, now, now);

        when(titleRepositoryPort.findPublishedBySlug("series-no-video")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(episode1));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(1);
        when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());

        PublicScreenTitleDetailDTO result = useCase.execute("series-no-video");

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).hasPlayableVideo()).isFalse();
        verify(videoDeliveryPort, never()).resolvePlaybackDelivery(any());
    }
}
