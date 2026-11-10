package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.InvalidScreenTimelineConfigurationException;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTimelineMode;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenTitleUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    @Mock
    private ScreenMediaValidationPort mediaValidationPort;

    @Mock
    private ClockPort clockPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    @Mock
    private ScreenMediaVisibilityPort mediaVisibilityPort;

    private CreateScreenTitleUseCase createUseCase;
    private UpdateScreenTitleUseCase updateUseCase;
    private PublishScreenTitleUseCase publishUseCase;
    private ArchiveScreenTitleUseCase archiveUseCase;
    private GetScreenTitleListUseCase getListUseCase;
    private GetScreenTitleDetailUseCase getDetailUseCase;

    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");
    private final Instant publishTime = Instant.parse("2026-10-06T12:00:00Z");
    private final Instant archiveTime = Instant.parse("2026-10-06T14:00:00Z");
    private final UUID titleId = UUID.randomUUID();
    private final UUID posterId = UUID.randomUUID();
    private final UUID backdropId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        createUseCase = new CreateScreenTitleUseCase(titleRepository, mediaValidationPort, clockPort);
        updateUseCase = new UpdateScreenTitleUseCase(titleRepository, mediaValidationPort, clockPort, entryRepositoryPort);
        publishUseCase = new PublishScreenTitleUseCase(titleRepository, entryRepositoryPort, mediaVisibilityPort, clockPort);
        archiveUseCase = new ArchiveScreenTitleUseCase(titleRepository, entryRepositoryPort, mediaVisibilityPort, clockPort);
        getListUseCase = new GetScreenTitleListUseCase(titleRepository);
        getDetailUseCase = new GetScreenTitleDetailUseCase(titleRepository);
    }

    // ==========================================
    // TITLE CREATE
    // ==========================================

    @Test
    @DisplayName("CreateTitle: creates valid draft ScreenTitle with publishedAt null and authoritative timestamp")
    void shouldCreateDraftScreenTitle() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "kiem-lai-phan-1", "Kiếm Lai Phần 1", "剑来", "Hành trình thiếu niên",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId
        );

        when(titleRepository.existsBySlug("kiem-lai-phan-1")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = createUseCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.id()).isNotNull();
        assertThat(result.slug()).isEqualTo("kiem-lai-phan-1");
        assertThat(result.title()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(result.originalTitle()).isEqualTo("剑来");
        assertThat(result.synopsis()).isEqualTo("Hành trình thiếu niên");
        assertThat(result.structure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(result.presentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(result.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(result.backdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(result.publishedAt()).isNull();
        assertThat(result.createdAt()).isEqualTo(now);
        assertThat(result.updatedAt()).isEqualTo(now);

        verify(mediaValidationPort).validateImageMediaAsset(posterId);
        verify(mediaValidationPort).validateImageMediaAsset(backdropId);
    }

    @Test
    @DisplayName("CreateTitle: creates valid draft ScreenTitle with totalEpisodeCount")
    void shouldCreateDraftScreenTitleWithTotalEpisodeCount() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "series-ep-count", "Series Ep Count", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, 24
        );

        when(titleRepository.existsBySlug("series-ep-count")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = createUseCase.execute(command);

        assertThat(result.totalEpisodeCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("CreateTitle: automatically generates normalized slug from Vietnamese title when slug is null")
    void shouldGenerateSlugAutomaticallyWhenSlugIsNull() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "Kiếm Lai: Đại Chiến Ma Giới (Đặc Biệt)", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.existsBySlug("kiem-lai-dai-chien-ma-gioi-dac-biet")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = createUseCase.execute(command);

        assertThat(result.slug()).isEqualTo("kiem-lai-dai-chien-ma-gioi-dac-biet");
    }

    @Test
    @DisplayName("CreateTitle: deterministically resolves slug collisions with incremental numeric suffixes")
    void shouldResolveSlugCollisionDeterministically() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "Kiếm Lai Phần 1", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.existsBySlug("kiem-lai-phan-1")).thenReturn(true);
        when(titleRepository.existsBySlug("kiem-lai-phan-1-2")).thenReturn(true);
        when(titleRepository.existsBySlug("kiem-lai-phan-1-3")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = createUseCase.execute(command);

        assertThat(result.slug()).isEqualTo("kiem-lai-phan-1-3");
    }

    @Test
    @DisplayName("CreateTitle: truncates base slug so numeric collision suffix does not exceed MAX_SLUG_LENGTH (180 chars)")
    void shouldResolveSlugCollisionWithinMaxSlugLengthWhenBaseSlugIsLong() {
        String longTitle = "a".repeat(200);
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                longTitle, null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        String maxBase = "a".repeat(180);
        when(titleRepository.existsBySlug(maxBase)).thenReturn(true);
        String expectedCandidate = "a".repeat(178) + "-2";
        when(titleRepository.existsBySlug(expectedCandidate)).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = createUseCase.execute(command);

        assertThat(result.slug()).isEqualTo(expectedCandidate);
        assertThat(result.slug().length()).isLessThanOrEqualTo(180);
    }

    @Test
    @DisplayName("CreateTitle: uses IdGeneratorPort when provided for server-side immutable identifier")
    void shouldUseIdGeneratorPortWhenProvided() {
        com.universe.shared.id.IdGeneratorPort customIdGenerator = org.mockito.Mockito.mock(com.universe.shared.id.IdGeneratorPort.class);
        UUID generatedId = UUID.randomUUID();
        when(customIdGenerator.generate()).thenReturn(generatedId);

        CreateScreenTitleUseCase customUseCase = new CreateScreenTitleUseCase(
                titleRepository, mediaValidationPort, clockPort, customIdGenerator
        );

        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.existsBySlug("kiem-lai")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = customUseCase.execute(command);

        assertThat(result.id()).isEqualTo(generatedId);
        verify(customIdGenerator).generate();
    }

    @ParameterizedTest(name = "Structure={0}, Presentation={1}")
    @MethodSource("provideApprovedClassificationCombinations")
    @DisplayName("CreateTitle: exhausts all approved structure and presentation combinations")
    void shouldCreateDraftWithAllApprovedClassifications(ScreenStructure structure, ScreenPresentation presentation) {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "title-slug", "Title Display", null, null,
                structure, presentation, null, null
        );

        when(titleRepository.existsBySlug("title-slug")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = createUseCase.execute(command);

        assertThat(result.structure()).isEqualTo(structure);
        assertThat(result.presentation()).isEqualTo(presentation);
        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(result.publishedAt()).isNull();
    }

    static Stream<Arguments> provideApprovedClassificationCombinations() {
        return Stream.of(
                Arguments.of(ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D),
                Arguments.of(ScreenStructure.SERIES, ScreenPresentation.LIVE_ACTION),
                Arguments.of(ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D),
                Arguments.of(ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION)
        );
    }

    @Test
    @DisplayName("CreateTitle: duplicate slug pre-check throws ScreenTitleSlugAlreadyExistsException")
    void shouldRejectDuplicateSlugPreCheck() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "duplicate-slug", "Title Display", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.existsBySlug("duplicate-slug")).thenReturn(true);

        assertThatThrownBy(() -> createUseCase.execute(command))
                .isInstanceOf(ScreenTitleSlugAlreadyExistsException.class)
                .hasMessageContaining("duplicate-slug");
    }

    @Test
    @DisplayName("CreateTitle: concurrent database unique race propagates ScreenTitleSlugAlreadyExistsException")
    void shouldPropagateDatabaseRaceSlugConflict() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "race-slug", "Title Display", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.existsBySlug("race-slug")).thenReturn(false);
        when(clockPort.now()).thenReturn(now);
        when(titleRepository.save(any(ScreenTitle.class)))
                .thenThrow(new ScreenTitleSlugAlreadyExistsException("race-slug"));

        assertThatThrownBy(() -> createUseCase.execute(command))
                .isInstanceOf(ScreenTitleSlugAlreadyExistsException.class)
                .hasMessageContaining("race-slug");
    }

    @Test
    @DisplayName("CreateTitle: media validation failure propagates cleanly")
    void shouldPropagateMediaValidationErrorOnCreate() {
        CreateScreenTitleCommand command = new CreateScreenTitleCommand(
                "media-err-slug", "Title Display", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, null
        );

        when(titleRepository.existsBySlug("media-err-slug")).thenReturn(false);
        doThrow(new ScreenMediaAssetNotFoundException(posterId))
                .when(mediaValidationPort).validateImageMediaAsset(posterId);

        assertThatThrownBy(() -> createUseCase.execute(command))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class)
                .hasMessageContaining(posterId.toString());
    }

    // ==========================================
    // TITLE EDIT
    // ==========================================

    @Test
    @DisplayName("UpdateTitle: updates metadata and persists changes")
    void shouldUpdateTitleMetadata() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "original-slug", "Original Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "renamed-slug", "Renamed Title", "Tên Gốc", "Tóm tắt mới",
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterId, backdropId
        );

        Instant updateTime = now.plusSeconds(3600);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(titleRepository.findBySlug("renamed-slug")).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(updateTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.id()).isEqualTo(titleId);
        assertThat(result.slug()).isEqualTo("renamed-slug");
        assertThat(result.title()).isEqualTo("Renamed Title");
        assertThat(result.originalTitle()).isEqualTo("Tên Gốc");
        assertThat(result.synopsis()).isEqualTo("Tóm tắt mới");
        assertThat(result.structure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(result.presentation()).isEqualTo(ScreenPresentation.LIVE_ACTION);
        assertThat(result.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(result.backdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(result.updatedAt()).isEqualTo(updateTime);
    }

    @Test
    @DisplayName("UpdateTitle: updates totalEpisodeCount successfully when valid")
    void shouldUpdateTitleWithTotalEpisodeCountWhenValid() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "series-slug", "Series Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, 24
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(12);
        when(clockPort.now()).thenReturn(now.plusSeconds(10));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.totalEpisodeCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("UpdateTitle: rejects totalEpisodeCount smaller than highest published episode number")
    void shouldRejectTotalEpisodeCountSmallerThanHighestPublishedEpisodeNumber() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "series-slug", "Series Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, 10
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(15);

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(InvalidScreenTitleTotalEpisodeCountException.class)
                .hasMessageContaining("10")
                .hasMessageContaining("15");
    }

    @Test
    @DisplayName("UpdateTitle: unrelated metadata update withoutEpisodeMetadata preserves existing totalEpisodeCount")
    void shouldPreserveTotalEpisodeCountOnUnrelatedMetadataUpdate() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
        );
        assertThat(existing.getTotalEpisodeCount()).isEqualTo(24);

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "series-slug", "Renamed Series Title", "Original Title", "New Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(10));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.title()).isEqualTo("Renamed Series Title");
        assertThat(result.totalEpisodeCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("UpdateTitle: intentional Admin action to clear totalEpisodeCount sets it to null for SERIES")
    void shouldIntentionallyClearTotalEpisodeCountForSeries() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
        );
        assertThat(existing.getTotalEpisodeCount()).isEqualTo(24);

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(10));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.totalEpisodeCount()).isNull();
    }

    @Test
    @DisplayName("UpdateTitle: converting SERIES to FEATURE clears totalEpisodeCount even if omitted")
    void shouldClearTotalEpisodeCountWhenConvertingSeriesToFeatureEvenIfOmitted() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
        );
        assertThat(existing.getTotalEpisodeCount()).isEqualTo(24);

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "series-slug", "Converted Feature Title", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterId, backdropId
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(10));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.structure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(result.totalEpisodeCount()).isNull();
    }

    @Test
    @DisplayName("UpdateTitle: legacy command without release metadata preserves all existing release metadata fields")
    void shouldPreserveReleaseMetadataOnLegacyCommandUpdate() {
        ScreenTitle existing = new ScreenTitle(
                titleId, "series-slug", "Original Series Title", "Original Original Title", "Original Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, now, now,
                24, ScreenAiringStatus.ONGOING, 2024, "Việt Nam", "Nguyên Tác", "Đạo Diễn", "Studio X"
        );

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "series-slug", "Updated Series Title", "New Original Title", "New Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId, 36
        );

        Instant updateTime = now.plusSeconds(120);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(10);
        when(clockPort.now()).thenReturn(updateTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.title()).isEqualTo("Updated Series Title");
        assertThat(result.totalEpisodeCount()).isEqualTo(36);
        assertThat(result.airingStatus()).isEqualTo(ScreenAiringStatus.ONGOING);
        assertThat(result.releaseYear()).isEqualTo(2024);
        assertThat(result.country()).isEqualTo("Việt Nam");
        assertThat(result.originalAuthor()).isEqualTo("Nguyên Tác");
        assertThat(result.director()).isEqualTo("Đạo Diễn");
        assertThat(result.productionStudio()).isEqualTo("Studio X");
    }

    @Test
    @DisplayName("UpdateTitle: withoutEpisodeMetadata factory preserves both totalEpisodeCount and release metadata")
    void shouldPreserveReleaseMetadataAndTotalEpisodeCountOnWithoutEpisodeMetadata() {
        ScreenTitle existing = new ScreenTitle(
                titleId, "series-slug", "Original Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, now, now,
                48, ScreenAiringStatus.COMPLETED, 2023, "Trung Quốc", "Tác Giả Y", "Đạo Diễn Y", "Studio Y"
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "series-slug", "Renamed Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId
        );

        Instant updateTime = now.plusSeconds(60);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(updateTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.title()).isEqualTo("Renamed Series Title");
        assertThat(result.totalEpisodeCount()).isEqualTo(48);
        assertThat(result.airingStatus()).isEqualTo(ScreenAiringStatus.COMPLETED);
        assertThat(result.releaseYear()).isEqualTo(2023);
        assertThat(result.country()).isEqualTo("Trung Quốc");
        assertThat(result.originalAuthor()).isEqualTo("Tác Giả Y");
        assertThat(result.director()).isEqualTo("Đạo Diễn Y");
        assertThat(result.productionStudio()).isEqualTo("Studio Y");
    }

    @Test
    @DisplayName("UpdateTitle: explicit release metadata update overwrites existing metadata")
    void shouldUpdateReleaseMetadataExplicitly() {
        ScreenTitle existing = new ScreenTitle(
                titleId, "feature-slug", "Old Movie Title", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, now, now,
                null, null, null, null, null, null, null
        );

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "feature-slug", "Updated Movie Title", "Movie Original", "Movie Synopsis",
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterId, backdropId,
                null, false,
                ScreenAiringStatus.RELEASED, 2025, "Hàn Quốc", "Tác Giả Hàn", "Đạo Diễn Hàn", "Cinema Studio"
        );

        Instant updateTime = now.plusSeconds(300);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(updateTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.airingStatus()).isEqualTo(ScreenAiringStatus.RELEASED);
        assertThat(result.releaseYear()).isEqualTo(2025);
        assertThat(result.country()).isEqualTo("Hàn Quốc");
        assertThat(result.originalAuthor()).isEqualTo("Tác Giả Hàn");
        assertThat(result.director()).isEqualTo("Đạo Diễn Hàn");
        assertThat(result.productionStudio()).isEqualTo("Cinema Studio");
    }

    @Test
    @DisplayName("UpdateTitle: explicit release metadata update intentionally clears fields when null")
    void shouldIntentionallyClearReleaseMetadata() {
        ScreenTitle existing = new ScreenTitle(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, now, now,
                24, ScreenAiringStatus.ONGOING, 2024, "Việt Nam", "Tác Giả", "Đạo Diễn", "Studio"
        );

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId,
                24, false,
                null, null, null, null, null, null
        );

        Instant updateTime = now.plusSeconds(300);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(updateTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.airingStatus()).isNull();
        assertThat(result.releaseYear()).isNull();
        assertThat(result.country()).isNull();
        assertThat(result.originalAuthor()).isNull();
        assertThat(result.director()).isNull();
        assertThat(result.productionStudio()).isNull();
    }

    @Test
    @DisplayName("UpdateTitle: changing structure with incompatible retained airing status is safely rejected")
    void shouldRejectIncompatibleStructureTransitionWithPreservedAiringStatus() {
        ScreenTitle existing = new ScreenTitle(
                titleId, "series-slug", "Series Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.DRAFT, null, now, now,
                24, ScreenAiringStatus.ONGOING, 2024, "Việt Nam", null, null, null
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "series-slug", "Converted Feature Title", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(10));

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không tương thích");

        verify(titleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UpdateTitle: keeping existing slug does not trigger duplicate conflict")
    void shouldAllowSameSlugOnUpdate() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "same-slug", "Original Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "same-slug", "Updated Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(3600));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.title()).isEqualTo("Updated Title");
        assertThat(result.slug()).isEqualTo("same-slug");
    }

    @Test
    @DisplayName("UpdateTitle: renaming title with null slug preserves established slug exactly")
    void shouldPreserveExistingSlugWhenRenamingTitleWithoutSlug() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "established-slug", "Old Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, null, "Brand New Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(3600));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.title()).isEqualTo("Brand New Title");
        assertThat(result.slug()).isEqualTo("established-slug");
    }

    @Test
    @DisplayName("UpdateTitle: duplicate slug owned by different title is rejected")
    void shouldRejectDuplicateSlugOnUpdate() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "slug-one", "Title One", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitle another = ScreenTitle.createDraft(
                UUID.randomUUID(), "slug-two", "Title Two", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "slug-two", "Title One Renamed", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(titleRepository.findBySlug("slug-two")).thenReturn(Optional.of(another));

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(ScreenTitleSlugAlreadyExistsException.class)
                .hasMessageContaining("slug-two");
    }

    @Test
    @DisplayName("UpdateTitle: editing published title preserves publishedAt timestamp exactly")
    void shouldPreservePublishedAtWhenEditingPublishedTitle() {
        ScreenTitle published = ScreenTitle.createDraft(
                titleId, "published-slug", "Published Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        published.publish(publishTime);

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "published-slug", "Edited Published Title", "Original", "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        Instant editTime = publishTime.plusSeconds(3600);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(published));
        when(clockPort.now()).thenReturn(editTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.title()).isEqualTo("Edited Published Title");
        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(result.publishedAt()).isEqualTo(publishTime);
        assertThat(result.updatedAt()).isEqualTo(editTime);
    }

    @Test
    @DisplayName("UpdateTitle: updating title with null poster/backdrop clears both references")
    void shouldClearPosterAndBackdropOnUpdate() {
        ScreenTitle existing = new ScreenTitle(
                titleId, "existing-slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, ScreenPublicationStatus.DRAFT, null, now, now
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "existing-slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(100));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = updateUseCase.execute(command);

        assertThat(result.posterMediaAssetId()).isNull();
        assertThat(result.backdropMediaAssetId()).isNull();

        ArgumentCaptor<ScreenTitle> captor = ArgumentCaptor.forClass(ScreenTitle.class);
        verify(titleRepository).save(captor.capture());
        assertThat(captor.getValue().getPosterMediaAssetId()).isNull();
        assertThat(captor.getValue().getBackdropMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("UpdateTitle: concurrent database unique race propagates ScreenTitleSlugAlreadyExistsException cleanly")
    void shouldPropagateDatabaseRaceSlugConflictOnUpdate() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "original-slug", "Original Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "race-slug", "Updated Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(titleRepository.findBySlug("race-slug")).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(now.plusSeconds(100));
        when(titleRepository.save(any(ScreenTitle.class)))
                .thenThrow(new ScreenTitleSlugAlreadyExistsException("race-slug"));

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(ScreenTitleSlugAlreadyExistsException.class)
                .hasMessageContaining("race-slug");
    }

    @Test
    @DisplayName("UpdateTitle: missing title throws ScreenTitleNotFoundException")
    void shouldThrowNotFoundOnUpdateMissingTitle() {
        UpdateScreenTitleCommand command = UpdateScreenTitleCommand.withoutEpisodeMetadata(
                titleId, "some-slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(ScreenTitleNotFoundException.class)
                .hasMessageContaining(titleId.toString());
    }

    // ==========================================
    // PUBLICATION LIFECYCLE
    // ==========================================

    @Test
    @DisplayName("PublishTitle: first publish transitions DRAFT to PUBLISHED with authoritative ClockPort timestamp")
    void shouldPublishDraftTitle() {
        ScreenTitle draft = ScreenTitle.createDraft(
                titleId, "draft-title", "Draft Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draft));
        when(clockPort.now()).thenReturn(publishTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = publishUseCase.execute(titleId);

        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(result.publishedAt()).isEqualTo(publishTime);
        assertThat(result.updatedAt()).isEqualTo(publishTime);
    }

    @Test
    @DisplayName("PublishTitle: publishing already published title throws IllegalStateException")
    void shouldRejectPublishingAlreadyPublishedTitle() {
        ScreenTitle published = ScreenTitle.createDraft(
                titleId, "published-title", "Published Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        published.publish(publishTime);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(published));
        when(clockPort.now()).thenReturn(publishTime.plusSeconds(100));

        assertThatThrownBy(() -> publishUseCase.execute(titleId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already published");
    }

    @Test
    @DisplayName("PublishTitle: publishing archived title throws IllegalStateException")
    void shouldRejectPublishingArchivedTitle() {
        ScreenTitle archived = ScreenTitle.createDraft(
                titleId, "archived-title", "Archived Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        archived.publish(publishTime);
        archived.archive(archiveTime);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(archived));
        when(clockPort.now()).thenReturn(archiveTime.plusSeconds(100));

        assertThatThrownBy(() -> publishUseCase.execute(titleId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be published");
    }

    @Test
    @DisplayName("ArchiveTitle: archiving published title sets ARCHIVED and preserves publishedAt")
    void shouldArchivePublishedTitleAndPreservePublishedAt() {
        ScreenTitle published = ScreenTitle.createDraft(
                titleId, "pub-title", "Pub Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        published.publish(publishTime);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(published));
        when(clockPort.now()).thenReturn(archiveTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = archiveUseCase.execute(titleId);

        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(result.publishedAt()).isEqualTo(publishTime);
        assertThat(result.updatedAt()).isEqualTo(archiveTime);
    }

    @Test
    @DisplayName("ArchiveTitle: archiving draft title sets ARCHIVED and keeps publishedAt null")
    void shouldArchiveDraftTitleWithNullPublishedAt() {
        ScreenTitle draft = ScreenTitle.createDraft(
                titleId, "draft-title", "Draft Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draft));
        when(clockPort.now()).thenReturn(archiveTime);
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        ScreenTitleDTO result = archiveUseCase.execute(titleId);

        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(result.publishedAt()).isNull();
        assertThat(result.updatedAt()).isEqualTo(archiveTime);
    }

    @Test
    @DisplayName("ArchiveTitle: archiving already archived title throws IllegalStateException")
    void shouldRejectArchivingAlreadyArchivedTitle() {
        ScreenTitle archived = ScreenTitle.createDraft(
                titleId, "archived-title", "Archived Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        archived.archive(archiveTime);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(archived));
        when(clockPort.now()).thenReturn(archiveTime.plusSeconds(100));

        assertThatThrownBy(() -> archiveUseCase.execute(titleId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already archived");
    }

    // ==========================================
    // ADMIN READ
    // ==========================================

    @Test
    @DisplayName("GetScreenTitleList: returns all titles across administrative states DRAFT, PUBLISHED, ARCHIVED")
    void shouldReturnAllAdministrativeStates() {
        ScreenTitle draft = ScreenTitle.createDraft(
                UUID.randomUUID(), "draft", "Draft", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitle published = ScreenTitle.createDraft(
                UUID.randomUUID(), "published", "Published", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        published.publish(publishTime);
        ScreenTitle archived = ScreenTitle.createDraft(
                UUID.randomUUID(), "archived", "Archived", ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, now
        );
        archived.archive(archiveTime);

        when(titleRepository.findAll()).thenReturn(List.of(draft, published, archived));

        List<ScreenTitleDTO> list = getListUseCase.execute();

        assertThat(list).hasSize(3);
        assertThat(list).extracting(ScreenTitleDTO::status)
                .containsExactly(ScreenPublicationStatus.DRAFT, ScreenPublicationStatus.PUBLISHED, ScreenPublicationStatus.ARCHIVED);
    }

    @Test
    @DisplayName("GetScreenTitleDetail: returns title detail by ID with all metadata and episode counts")
    void shouldReturnTitleDetail() {
        ScreenTitle title = ScreenTitle.createDraft(
                titleId, "detail-title", "Detail Title", "Original Title", "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId, now,
                24, ScreenAiringStatus.ONGOING, 2024,
                "Trung Quốc", "Phong Hỏa Hí Chư Hầu", "Đạo Diễn A", "Studio B"
        );

        when(titleRepository.findById(titleId)).thenReturn(Optional.of(title));

        ScreenTitleDTO dto = getDetailUseCase.execute(titleId);

        assertThat(dto.id()).isEqualTo(titleId);
        assertThat(dto.title()).isEqualTo("Detail Title");
        assertThat(dto.slug()).isEqualTo("detail-title");
        assertThat(dto.totalEpisodeCount()).isEqualTo(24);
        assertThat(dto.airingStatus()).isEqualTo(ScreenAiringStatus.ONGOING);
        assertThat(dto.releaseYear()).isEqualTo(2024);
        assertThat(dto.country()).isEqualTo("Trung Quốc");
        assertThat(dto.originalAuthor()).isEqualTo("Phong Hỏa Hí Chư Hầu");
        assertThat(dto.director()).isEqualTo("Đạo Diễn A");
        assertThat(dto.productionStudio()).isEqualTo("Studio B");
    }

    @Test
    @DisplayName("GetScreenTitleDetail: missing title throws ScreenTitleNotFoundException")
    void shouldThrowNotFoundOnGetDetail() {
        when(titleRepository.findById(titleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> getDetailUseCase.execute(titleId))
                .isInstanceOf(ScreenTitleNotFoundException.class)
                .hasMessageContaining(titleId.toString());
    }

    // ==========================================
    // UX02-C2: TITLE DEFAULT TIMELINE MANAGEMENT
    // ==========================================

    @Test
    @DisplayName("UX02-C2 UpdateTitle: updates default Intro and Outro timeline and maps formatted timestamps on DTO")
    void shouldUpdateDefaultTimelineSuccessfully() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(60));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", "剑来", "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                24, true,
                true, ScreenAiringStatus.ONGOING, 2024, "Trung Quốc", "Phong Hỏa Hí Chư Hầu", "Đạo diễn", "Studio",
                true, 0L, 105L, 1210L
        );

        ScreenTitleDTO dto = updateUseCase.execute(command);

        assertThat(dto.defaultIntroStartSeconds()).isEqualTo(0L);
        assertThat(dto.defaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(dto.defaultOutroStartSeconds()).isEqualTo(1210L);
        assertThat(dto.hasDefaultIntro()).isTrue();
        assertThat(dto.hasDefaultOutro()).isTrue();
        assertThat(dto.formattedDefaultIntroStart()).isEqualTo("00:00");
        assertThat(dto.formattedDefaultIntroEnd()).isEqualTo("01:45");
        assertThat(dto.formattedDefaultOutroStart()).isEqualTo("20:10");
    }

    @Test
    @DisplayName("UX02-C2 UpdateTitle: clearing default Intro while keeping default Outro updates cleanly")
    void shouldClearDefaultIntroWhileKeepingDefaultOutro() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        existing.updateDefaultTimeline(0L, 90L, 1200L, now);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(60));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                24, true,
                false, null, null, null, null, null, null,
                true, null, null, 1300L
        );

        ScreenTitleDTO dto = updateUseCase.execute(command);

        assertThat(dto.defaultIntroStartSeconds()).isNull();
        assertThat(dto.defaultIntroEndSeconds()).isNull();
        assertThat(dto.defaultOutroStartSeconds()).isEqualTo(1300L);
        assertThat(dto.hasDefaultIntro()).isFalse();
        assertThat(dto.hasDefaultOutro()).isTrue();
    }

    @Test
    @DisplayName("UX02-C2 UpdateTitle: preserves existing default timeline when updateDefaultTimeline is false")
    void shouldPreserveExistingDefaultTimelineWhenFlagIsFalse() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        existing.updateDefaultTimeline(15L, 105L, 1250L, now);
        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(60));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateScreenTitleCommand legacyCommand = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai Mới", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, 24
        );

        ScreenTitleDTO dto = updateUseCase.execute(legacyCommand);

        assertThat(dto.title()).isEqualTo("Kiếm Lai Mới");
        assertThat(dto.defaultIntroStartSeconds()).isEqualTo(15L);
        assertThat(dto.defaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(dto.defaultOutroStartSeconds()).isEqualTo(1250L);
    }

    @Test
    @DisplayName("UX02-C2 UpdateTitle: rejects title default update when an inheriting entry has an override Outro before the new default Intro end")
    void shouldRejectTitleDefaultUpdateWhenInheritingEntryHasConflictingOverride() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenEntry ep1 = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1", now
        );
        ep1.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.OVERRIDE, 80L, now);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(ep1));

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 105L, 1200L
        );

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                .hasMessage("Outro không được bắt đầu trước khi Intro kết thúc. (Xung đột với tập \"Tập 1\").");
        verify(titleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UX02-C2 UpdateTitle: rejects title default update when an inheriting entry duration is exceeded")
    void shouldRejectTitleDefaultUpdateWhenInheritingEntryDurationIsExceeded() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenEntry ep1 = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1000L, now
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(ep1));

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, 1200L
        );

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                .hasMessage("Thời gian bắt đầu Outro phải nhỏ hơn thời lượng tập phim. (Tập \"Tập 1\": 1000 giây).");
        verify(titleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UX02-C2.1 UpdateTitle: rejects title default update when an inheriting entry duration is exceeded by Outro end")
    void shouldRejectTitleDefaultUpdateWhenInheritingEntryDurationExceededByOutroEnd() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenEntry ep1 = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1500L, now
        );

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(ep1));

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, 1400L, 1600L
        );

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                .hasMessage("Thời gian kết thúc Outro không được vượt quá thời lượng tập phim. (Tập \"Tập 1\": 1500 giây).");
        verify(titleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UX02-C2 UpdateTitle: allows title default update when shorter entry has DISABLED the segment")
    void shouldAllowTitleDefaultUpdateWhenShorterEntryDisabledSegment() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenEntry ep1 = ScreenEntry.createDraft(
                UUID.randomUUID(), titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, 1000L, now
        );
        ep1.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.DISABLED, null, now);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(60));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of(ep1));
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, 1200L
        );

        ScreenTitleDTO dto = updateUseCase.execute(command);
        assertThat(dto.defaultOutroStartSeconds()).isEqualTo(1200L);
    }

    @Test
    @DisplayName("UX02-C2.1-SR01 Defect B Regression: preserves stored defaultOutroEnd when legacy command omits outroEnd")
    void shouldPreserveStoredDefaultOutroEndWhenLegacyCommandOmitsOutroEnd() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        existing.updateDefaultTimeline(0L, 90L, 1200L, 1350L, now);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(60));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        // Legacy 22-arg command omits defaultOutroEnd (updateDefaultOutroEnd = false)
        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, 1200L
        );

        ScreenTitleDTO dto = updateUseCase.execute(command);
        assertThat(dto.defaultOutroStartSeconds()).isEqualTo(1200L);
        assertThat(dto.defaultOutroEndSeconds()).isEqualTo(1350L);
    }

    @Test
    @DisplayName("UX02-C2.1-SR01 Defect B Regression: clears stored defaultOutroEnd when command explicitly sets it with updateDefaultOutroEnd=true")
    void shouldClearStoredDefaultOutroEndWhenCommandExplicitlySetsItWithUpdateDefaultOutroEndTrue() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        existing.updateDefaultTimeline(0L, 90L, 1200L, 1350L, now);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(now.plusSeconds(60));
        when(entryRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
        when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

        // 23-arg command with explicit defaultOutroEnd = null (updateDefaultOutroEnd = true)
        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, 1200L, null
        );

        ScreenTitleDTO dto = updateUseCase.execute(command);
        assertThat(dto.defaultOutroStartSeconds()).isEqualTo(1200L);
        assertThat(dto.defaultOutroEndSeconds()).isNull();
    }

    @Test
    @DisplayName("UX02-C2.1-SR01 Defect B Regression: rejects legacy command when defaultOutroStart changed to after stored defaultOutroEnd")
    void shouldRejectLegacyCommandWhenDefaultOutroStartChangedToAfterStoredDefaultOutroEnd() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        existing.updateDefaultTimeline(0L, 90L, 1200L, 1350L, now);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));

        // Legacy command updates outroStart to 1400L (> stored outroEnd 1350L), updateDefaultOutroEnd = false
        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, 1400L
        );

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                .hasMessage("Thời gian kết thúc Outro phải lớn hơn thời gian bắt đầu.");
        verify(titleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UX02-C2.1-SR01 Defect B Regression: rejects legacy command clearing defaultOutroStart while stored defaultOutroEnd exists")
    void shouldRejectLegacyCommandClearingDefaultOutroStartWhileStoredDefaultOutroEndExists() {
        ScreenTitle existing = ScreenTitle.createDraft(
                titleId, "kiem-lai", "Kiếm Lai", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        existing.updateDefaultTimeline(0L, 90L, 1200L, 1350L, now);

        when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(existing));

        // Legacy command clears outroStart to null while omitting outroEnd (updateDefaultOutroEnd = false)
        UpdateScreenTitleCommand command = new UpdateScreenTitleCommand(
                titleId, "kiem-lai", "Kiếm Lai", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                null, true,
                false, null, null, null, null, null, null,
                true, 0L, 90L, null
        );

        assertThatThrownBy(() -> updateUseCase.execute(command))
                .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                .hasMessage("Không thể thiết lập thời gian kết thúc Outro khi chưa có thời gian bắt đầu.");
        verify(titleRepository, never()).save(any());
    }
}
