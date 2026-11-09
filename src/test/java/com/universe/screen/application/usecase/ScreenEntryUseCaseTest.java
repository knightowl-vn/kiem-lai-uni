package com.universe.screen.application.usecase;

import com.universe.screen.application.commands.CreateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.InvalidScreenTimelineConfigurationException;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenEntry Application Use Case Tests")
class ScreenEntryUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    @Mock
    private ScreenMediaVisibilityPort mediaVisibilityPort;

    @Mock
    private ClockPort clockPort;

    private final UUID seriesTitleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID featureTitleId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID foreignTitleId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID entryId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final Instant now = Instant.parse("2026-10-06T10:00:00Z");

    private ScreenTitle seriesTitle;
    private ScreenTitle featureTitle;

    @BeforeEach
    void setUp() {
        seriesTitle = ScreenTitle.createDraft(
                seriesTitleId, "series-slug", "Series Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        featureTitle = ScreenTitle.createDraft(
                featureTitleId, "movie-slug", "Movie Title", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, now
        );
    }

    @Nested
    @DisplayName("GetScreenEntryListUseCase")
    class GetListTests {

        @Test
        @DisplayName("Should throw ScreenTitleNotFoundException when title does not exist")
        void shouldThrowWhenTitleNotFound() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(false);

            GetScreenEntryListUseCase useCase = new GetScreenEntryListUseCase(titleRepository, entryRepository);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
        }

        @Test
        @DisplayName("Should return entries mapped to DTOs in deterministic order")
        void shouldReturnEntries() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByTitleId(seriesTitleId)).thenReturn(List.of(entry));

            GetScreenEntryListUseCase useCase = new GetScreenEntryListUseCase(titleRepository, entryRepository);
            List<ScreenEntryDTO> dtos = useCase.execute(seriesTitleId);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).id()).isEqualTo(entryId);
            assertThat(dtos.get(0).title()).isEqualTo("Ep 1");
            assertThat(dtos.get(0).type()).isEqualTo(ScreenEntryType.EPISODE);
            assertThat(dtos.get(0).number()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("GetScreenEntryDetailUseCase")
    class GetDetailTests {

        @Test
        @DisplayName("Should throw ScreenTitleNotFoundException when title does not exist")
        void shouldThrowWhenTitleNotFound() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(false);

            GetScreenEntryDetailUseCase useCase = new GetScreenEntryDetailUseCase(titleRepository, entryRepository);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw ScreenEntryNotFoundException when entry does not exist")
        void shouldThrowWhenEntryNotFound() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);
            when(entryRepository.findById(entryId)).thenReturn(Optional.empty());

            GetScreenEntryDetailUseCase useCase = new GetScreenEntryDetailUseCase(titleRepository, entryRepository);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenEntryNotFoundException.class);
        }

        @Test
        @DisplayName("Ownership: Should throw ScreenEntryNotFoundException when entry belongs to foreign title")
        void shouldThrowWhenEntryBelongsToForeignTitle() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);

            ScreenEntry foreignEntry = ScreenEntry.createDraft(entryId, foreignTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findById(entryId)).thenReturn(Optional.of(foreignEntry));

            GetScreenEntryDetailUseCase useCase = new GetScreenEntryDetailUseCase(titleRepository, entryRepository);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenEntryNotFoundException.class);
        }

        @Test
        @DisplayName("Should return entry DTO when valid and owned by title")
        void shouldReturnDetail() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));

            GetScreenEntryDetailUseCase useCase = new GetScreenEntryDetailUseCase(titleRepository, entryRepository);
            ScreenEntryDTO dto = useCase.execute(seriesTitleId, entryId);

            assertThat(dto).isNotNull();
            assertThat(dto.id()).isEqualTo(entryId);
            assertThat(dto.titleId()).isEqualTo(seriesTitleId);
            assertThat(dto.slug()).isEqualTo("ep-1");
        }
    }

    @Nested
    @DisplayName("CreateScreenEntryUseCase")
    class CreateTests {

        @Test
        @DisplayName("Should throw ScreenTitleNotFoundException when title does not exist")
        void shouldThrowWhenTitleNotFound() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.empty());

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Validation: CreateScreenEntryCommand with type == null should fail with IllegalArgumentException and not save")
        void shouldRejectNullTypeOnCreate() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(seriesTitleId, "ep-1", null, 1, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type is required");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Compatibility: Should reject FEATURE entry on SERIES title")
        void shouldRejectFeatureOnSeriesTitle() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(seriesTitleId, "movie", ScreenEntryType.FEATURE, null, "Movie", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Compatibility: Should reject EPISODE entry on FEATURE title (movies must not be fake Episode 1)")
        void shouldRejectEpisodeOnFeatureTitle() {
            when(titleRepository.findById(featureTitleId)).thenReturn(Optional.of(featureTitle));

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(featureTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Uniqueness: Should reject duplicate slug scoped to title")
        void shouldRejectDuplicateSlug() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            when(entryRepository.existsByTitleIdAndSlug(seriesTitleId, "ep-1")).thenReturn(true);

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(ScreenEntrySlugAlreadyExistsException.class)
                    .hasMessageContaining("ep-1");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Number validation: EPISODE requires non-null positive number")
        void shouldRequirePositiveNumberForEpisode() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            when(entryRepository.existsByTitleIdAndSlug(seriesTitleId, "ep-null")).thenReturn(false);

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);

            // Null number
            CreateScreenEntryCommand nullCmd = new CreateScreenEntryCommand(seriesTitleId, "ep-null", ScreenEntryType.EPISODE, null, "Ep", null, null);
            assertThatThrownBy(() -> useCase.execute(nullCmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a non-null entry number");

            // Zero number
            CreateScreenEntryCommand zeroCmd = new CreateScreenEntryCommand(seriesTitleId, "ep-null", ScreenEntryType.EPISODE, 0, "Ep", null, null);
            assertThatThrownBy(() -> useCase.execute(zeroCmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Number validation: FEATURE requires null number (movie is not fake Episode 1)")
        void shouldRequireNullNumberForFeature() {
            when(titleRepository.findById(featureTitleId)).thenReturn(Optional.of(featureTitle));
            when(entryRepository.existsByTitleIdAndSlug(featureTitleId, "movie")).thenReturn(false);

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(featureTitleId, "movie", ScreenEntryType.FEATURE, 1, "Movie", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FEATURE entry must not have an entry number");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should create valid EPISODE entry in DRAFT status with publishedAt null")
        void shouldCreateValidEpisodeEntry() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            when(entryRepository.existsByTitleIdAndSlug(seriesTitleId, "ep-1")).thenReturn(false);
            when(clockPort.now()).thenReturn(now);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Tóm tắt", 1200L);

            ScreenEntryDTO created = useCase.execute(cmd);

            assertThat(created).isNotNull();
            assertThat(created.titleId()).isEqualTo(seriesTitleId);
            assertThat(created.slug()).isEqualTo("ep-1");
            assertThat(created.type()).isEqualTo(ScreenEntryType.EPISODE);
            assertThat(created.number()).isEqualTo(1);
            assertThat(created.title()).isEqualTo("Tập 1");
            assertThat(created.synopsis()).isEqualTo("Tóm tắt");
            assertThat(created.durationSeconds()).isEqualTo(1200L);
            assertThat(created.status()).isEqualTo(ScreenPublicationStatus.DRAFT);
            assertThat(created.publishedAt()).isNull();
            assertThat(created.videoMediaAssetId()).isNull();
            assertThat(created.thumbnailMediaAssetId()).isNull();
        }

        @Test
        @DisplayName("Should create valid FEATURE entry on FEATURE title with null number")
        void shouldCreateValidFeatureEntry() {
            when(titleRepository.findById(featureTitleId)).thenReturn(Optional.of(featureTitle));
            when(entryRepository.existsByTitleIdAndSlug(featureTitleId, "full-movie")).thenReturn(false);
            when(clockPort.now()).thenReturn(now);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            CreateScreenEntryCommand cmd = new CreateScreenEntryCommand(featureTitleId, "full-movie", ScreenEntryType.FEATURE, null, "Phim Chiếu Rạp", null, 7200L);

            ScreenEntryDTO created = useCase.execute(cmd);

            assertThat(created).isNotNull();
            assertThat(created.titleId()).isEqualTo(featureTitleId);
            assertThat(created.type()).isEqualTo(ScreenEntryType.FEATURE);
            assertThat(created.number()).isNull();
            assertThat(created.status()).isEqualTo(ScreenPublicationStatus.DRAFT);
        }

        @Test
        @DisplayName("Should create valid SPECIAL and TRAILER on SERIES with nullable numbers")
        void shouldCreateValidSpecialAndTrailer() {
            when(titleRepository.findById(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            when(clockPort.now()).thenReturn(now);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateScreenEntryUseCase useCase = new CreateScreenEntryUseCase(titleRepository, entryRepository, clockPort);

            // Special with null number
            when(entryRepository.existsByTitleIdAndSlug(seriesTitleId, "special-1")).thenReturn(false);
            ScreenEntryDTO spec = useCase.execute(new CreateScreenEntryCommand(seriesTitleId, "special-1", ScreenEntryType.SPECIAL, null, "Special", null, null));
            assertThat(spec.number()).isNull();

            // Trailer with number
            when(entryRepository.existsByTitleIdAndSlug(seriesTitleId, "trailer-1")).thenReturn(false);
            ScreenEntryDTO trailer = useCase.execute(new CreateScreenEntryCommand(seriesTitleId, "trailer-1", ScreenEntryType.TRAILER, 1, "Trailer 1", null, null));
            assertThat(trailer.number()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("UpdateScreenEntryUseCase")
    class UpdateTests {

        @Test
        @DisplayName("Should throw ScreenTitleNotFoundException when title does not exist")
        void shouldThrowWhenTitleNotFound() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.empty());

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw ScreenEntryNotFoundException when entry does not exist")
        void shouldThrowWhenEntryNotFound() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(ScreenEntryNotFoundException.class);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ownership: Should throw ScreenEntryNotFoundException and reject mutation when entry belongs to foreign title")
        void shouldRejectForeignTitleOwnership() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            // Entry belongs to foreignTitleId, not seriesTitleId
            ScreenEntry foreignEntry = ScreenEntry.createDraft(entryId, foreignTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(foreignEntry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(seriesTitleId, entryId, "ep-1-updated", ScreenEntryType.EPISODE, 1, "Ep 1 Updated", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(ScreenEntryNotFoundException.class);

            // Verify entry remains unchanged and save is NEVER called
            assertThat(foreignEntry.getTitle()).isEqualTo("Ep 1");
            assertThat(foreignEntry.getSlug()).isEqualTo("ep-1");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Validation: UpdateScreenEntryCommand with type == null should fail with IllegalArgumentException, leave entry unchanged, and not save")
        void shouldRejectNullTypeOnUpdate() {
            ScreenEntry existing = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Original Title", now);
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(existing));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(seriesTitleId, entryId, "ep-1-edit", null, 1, "New Title", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type is required");

            // existing entry remains unchanged
            assertThat(existing.getTitle()).isEqualTo("Original Title");
            assertThat(existing.getType()).isEqualTo(ScreenEntryType.EPISODE);
            assertThat(existing.getSlug()).isEqualTo("ep-1");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Compatibility: Should reject incompatible type on update")
        void shouldRejectIncompatibleTypeOnUpdate() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            // Updating to FEATURE on SERIES title is incompatible
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(seriesTitleId, entryId, "ep-1", ScreenEntryType.FEATURE, null, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Uniqueness: Should reject duplicate slug belonging to another entry under same title")
        void shouldRejectDuplicateSlugOnUpdate() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UUID otherEntryId = UUID.randomUUID();
            ScreenEntry otherEntry = ScreenEntry.createDraft(otherEntryId, seriesTitleId, "ep-2", ScreenEntryType.EPISODE, 2, "Ep 2", now);
            when(entryRepository.findByTitleIdAndSlug(seriesTitleId, "ep-2")).thenReturn(Optional.of(otherEntry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(seriesTitleId, entryId, "ep-2", ScreenEntryType.EPISODE, 1, "Ep 1", null, null);

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(ScreenEntrySlugAlreadyExistsException.class)
                    .hasMessageContaining("ep-2");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("CRITICAL — PRESERVE MEDIA REFERENCES: Updating metadata must preserve existing videoMediaAssetId and thumbnailMediaAssetId")
        void shouldPreserveExistingMediaAssetReferencesOnMetadataUpdate() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            UUID existingVideoId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            UUID existingThumbId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

            ScreenEntry existingEntry = new ScreenEntry(
                    entryId,
                    seriesTitleId,
                    "ep-1",
                    ScreenEntryType.EPISODE,
                    1,
                    "Ep 1 Original",
                    "Original synopsis",
                    existingVideoId,
                    existingThumbId,
                    1000L,
                    ScreenPublicationStatus.DRAFT,
                    null,
                    now,
                    now
            );
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(existingEntry));
            when(clockPort.now()).thenReturn(now.plusSeconds(3600));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId,
                    entryId,
                    "ep-1-updated",
                    ScreenEntryType.EPISODE,
                    1,
                    "Ep 1 Renamed",
                    "New synopsis",
                    1200L
            );

            ScreenEntryDTO updated = useCase.execute(cmd);

            // Metadata updated
            assertThat(updated.title()).isEqualTo("Ep 1 Renamed");
            assertThat(updated.slug()).isEqualTo("ep-1-updated");
            assertThat(updated.synopsis()).isEqualTo("New synopsis");
            assertThat(updated.durationSeconds()).isEqualTo(1200L);

            // Media references preserved exactly
            assertThat(updated.videoMediaAssetId()).isEqualTo(existingVideoId);
            assertThat(updated.thumbnailMediaAssetId()).isEqualTo(existingThumbId);
            assertThat(existingEntry.getVideoMediaAssetId()).isEqualTo(existingVideoId);
            assertThat(existingEntry.getThumbnailMediaAssetId()).isEqualTo(existingThumbId);
        }

        @Test
        @DisplayName("UpdateEntry on PUBLISHED episode: rejects changing number to exceed declared total (20 -> 25 when total = 24)")
        void shouldRejectUpdatingPublishedEpisodeNumberToExceedDeclaredTotal() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry publishedEntry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-20", ScreenEntryType.EPISODE, 20, "Ep 20", now
            );
            publishedEntry.publish(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(publishedEntry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-25", ScreenEntryType.EPISODE, 25, "Ep 25", null, null
            );

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(InvalidScreenTitleTotalEpisodeCountException.class)
                    .hasMessageContaining("25")
                    .hasMessageContaining("24");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("UpdateEntry on PUBLISHED episode: allows updating number to exact declared total (20 -> 24 when total = 24)")
        void shouldAllowUpdatingPublishedEpisodeNumberToExactDeclaredTotal() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry publishedEntry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-20", ScreenEntryType.EPISODE, 20, "Ep 20", now
            );
            publishedEntry.publish(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(publishedEntry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-24", ScreenEntryType.EPISODE, 24, "Ep 24", null, null
            );

            ScreenEntryDTO updated = useCase.execute(cmd);
            assertThat(updated.number()).isEqualTo(24);
            assertThat(updated.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        }

        @Test
        @DisplayName("UpdateEntry on PUBLISHED episode: allows updating number when total is unknown (20 -> 25 when total = null)")
        void shouldAllowUpdatingPublishedEpisodeNumberWhenTotalIsUnknown() {
            ScreenTitle seriesWithoutTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, null
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithoutTotal));

            ScreenEntry publishedEntry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-20", ScreenEntryType.EPISODE, 20, "Ep 20", now
            );
            publishedEntry.publish(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(publishedEntry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-25", ScreenEntryType.EPISODE, 25, "Ep 25", null, null
            );

            ScreenEntryDTO updated = useCase.execute(cmd);
            assertThat(updated.number()).isEqualTo(25);
            assertThat(updated.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        }

        @Test
        @DisplayName("UpdateEntry on DRAFT episode: allows changing number beyond declared total (enforced at publication time)")
        void shouldAllowUpdatingDraftEpisodeNumberToExceedDeclaredTotal() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry draftEntry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-20", ScreenEntryType.EPISODE, 20, "Ep 20", now
            );
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(draftEntry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-25", ScreenEntryType.EPISODE, 25, "Ep 25", null, null
            );

            ScreenEntryDTO updated = useCase.execute(cmd);
            assertThat(updated.number()).isEqualTo(25);
            assertThat(updated.status()).isEqualTo(ScreenPublicationStatus.DRAFT);
        }

        @Test
        @DisplayName("Lock order: Parent ScreenTitle lock is acquired before ScreenEntry lock and mutation")
        void shouldAcquireParentTitleLockBeforeScreenEntryLockAndMutation() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry publishedEntry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-20", ScreenEntryType.EPISODE, 20, "Ep 20", now
            );
            publishedEntry.publish(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(publishedEntry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-22", ScreenEntryType.EPISODE, 22, "Ep 22", null, null
            );

            useCase.execute(cmd);

            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(titleRepository, entryRepository);
            inOrder.verify(titleRepository).findByIdForUpdate(seriesTitleId);
            inOrder.verify(entryRepository).findByIdForUpdate(entryId);
            inOrder.verify(entryRepository).save(any(ScreenEntry.class));
        }

        @Test
        @DisplayName("UpdateEntry on PUBLISHED episode: allows updating number within declared total")
        void shouldAllowUpdatingPublishedEpisodeNumberWithinDeclaredTotal() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry publishedEntry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-20", ScreenEntryType.EPISODE, 20, "Ep 20", now
            );
            publishedEntry.publish(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(publishedEntry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-22", ScreenEntryType.EPISODE, 22, "Ep 22", null, null
            );

            ScreenEntryDTO updated = useCase.execute(cmd);
            assertThat(updated.number()).isEqualTo(22);
            assertThat(updated.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        }

        @Test
        @DisplayName("UX02-C2 UpdateEntry: updates entry timeline modes (OVERRIDE / DISABLED) and exposes formatted timestamps")
        void shouldUpdateEntryTimelineModesAndExposeFormattedTimestamps() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1500L, now
            );
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(100));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", "Synopsis", 1500L,
                    true, ScreenTimelineMode.OVERRIDE, 15L, 110L, ScreenTimelineMode.DISABLED, null
            );

            ScreenEntryDTO updated = useCase.execute(cmd);

            assertThat(updated.introMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
            assertThat(updated.introStartSeconds()).isEqualTo(15L);
            assertThat(updated.introEndSeconds()).isEqualTo(110L);
            assertThat(updated.formattedIntroStart()).isEqualTo("00:15");
            assertThat(updated.formattedIntroEnd()).isEqualTo("01:50");
            assertThat(updated.outroMode()).isEqualTo(ScreenTimelineMode.DISABLED);
            assertThat(updated.outroStartSeconds()).isNull();
            assertThat(updated.formattedOutroStart()).isNull();
        }

        @Test
        @DisplayName("UX02-C2 UpdateEntry: transitioning OVERRIDE -> INHERIT clears entry override timestamps while keeping title defaults untouched")
        void shouldClearOverrideTimestampsWhenTransitioningOverrideToInherit() {
            seriesTitle.updateDefaultTimeline(0L, 90L, 1200L, now);
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1500L, now
            );
            entry.updateTimeline(ScreenTimelineMode.OVERRIDE, 10L, 100L, ScreenTimelineMode.OVERRIDE, 1300L, now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(100));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, 1500L,
                    true, ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.INHERIT, null
            );

            ScreenEntryDTO updated = useCase.execute(cmd);

            assertThat(updated.introMode()).isEqualTo(ScreenTimelineMode.INHERIT);
            assertThat(updated.introStartSeconds()).isNull();
            assertThat(updated.introEndSeconds()).isNull();
            assertThat(updated.outroMode()).isEqualTo(ScreenTimelineMode.INHERIT);
            assertThat(updated.outroStartSeconds()).isNull();
            // Title defaults remain untouched
            assertThat(seriesTitle.getDefaultIntroStartSeconds()).isEqualTo(0L);
            assertThat(seriesTitle.getDefaultIntroEndSeconds()).isEqualTo(90L);
            assertThat(seriesTitle.getDefaultOutroStartSeconds()).isEqualTo(1200L);
        }

        @Test
        @DisplayName("UX02-C2 UpdateEntry: transitioning DISABLED -> OVERRIDE applies custom timestamps")
        void shouldApplyCustomTimestampsWhenTransitioningDisabledToOverride() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1500L, now
            );
            entry.updateTimeline(ScreenTimelineMode.DISABLED, null, null, ScreenTimelineMode.DISABLED, null, now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(100));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, 1500L,
                    true, ScreenTimelineMode.OVERRIDE, 0L, 85L, ScreenTimelineMode.OVERRIDE, 1280L
            );

            ScreenEntryDTO updated = useCase.execute(cmd);

            assertThat(updated.introMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
            assertThat(updated.introStartSeconds()).isEqualTo(0L);
            assertThat(updated.introEndSeconds()).isEqualTo(85L);
            assertThat(updated.outroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
            assertThat(updated.outroStartSeconds()).isEqualTo(1280L);
        }

        @Test
        @DisplayName("UX02-C2 UpdateEntry: legacy command constructor (updateTimeline = false) preserves existing timeline metadata")
        void shouldPreserveExistingTimelineWhenUpdateTimelineIsFalse() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1500L, now
            );
            entry.updateTimeline(ScreenTimelineMode.OVERRIDE, 5L, 95L, ScreenTimelineMode.DISABLED, null, now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(100));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand legacyCmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1 Updated", "New synopsis", 1500L
            );

            ScreenEntryDTO updated = useCase.execute(legacyCmd);

            assertThat(updated.title()).isEqualTo("Ep 1 Updated");
            assertThat(updated.introMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
            assertThat(updated.introStartSeconds()).isEqualTo(5L);
            assertThat(updated.introEndSeconds()).isEqualTo(95L);
            assertThat(updated.outroMode()).isEqualTo(ScreenTimelineMode.DISABLED);
            assertThat(updated.outroStartSeconds()).isNull();
        }

        @Test
        @DisplayName("UX02-C2 UpdateEntry: rejects combined timeline when inherited Intro overlaps with entry override Outro")
        void shouldRejectCombinedTimelineOverlapBetweenInheritedIntroAndOverrideOutro() {
            seriesTitle.updateDefaultTimeline(0L, 105L, 1200L, now);
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1500L, now
            );
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, 1500L,
                    true, ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.OVERRIDE, 90L
            );

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                    .hasMessage("Outro không được bắt đầu trước khi Intro kết thúc.");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("UX02-C2 UpdateEntry: rejects combined timeline when effective Outro exceeds known durationSeconds")
        void shouldRejectCombinedTimelineWhenEffectiveOutroExceedsDurationSeconds() {
            seriesTitle.updateDefaultTimeline(0L, 90L, 1400L, now);
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1200L, now
            );
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, 1200L,
                    true, ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.INHERIT, null
            );

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                    .hasMessage("Thời gian bắt đầu Outro phải nhỏ hơn thời lượng tập phim.");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("UX02-C2-SR01 Defect 2 Regression: rejects legacy command when duration shortened below existing overridden Intro end")
        void shouldRejectLegacyCommandWhenDurationShortenedBelowOverriddenIntroEnd() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1200L, now
            );
            entry.updateTimeline(ScreenTimelineMode.OVERRIDE, 20L, 120L, ScreenTimelineMode.INHERIT, null, now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            // Legacy command (updateTimeline = false) shortens duration to 90 seconds (below introEnd 120s)
            UpdateScreenEntryCommand legacyCmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1 Updated", null, 90L
            );

            assertThatThrownBy(() -> useCase.execute(legacyCmd))
                    .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                    .hasMessage("Thời gian kết thúc Intro không được vượt quá thời lượng tập phim.");

            // Aggregate unmutated and never saved
            assertThat(entry.getDurationSeconds()).isEqualTo(1200L);
            assertThat(entry.getIntroMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
            assertThat(entry.getIntroStartSeconds()).isEqualTo(20L);
            assertThat(entry.getIntroEndSeconds()).isEqualTo(120L);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("UX02-C2-SR01 Defect 2 Regression: rejects legacy command when duration shortened to or before inherited Outro start")
        void shouldRejectLegacyCommandWhenDurationShortenedToOrBeforeInheritedOutroStart() {
            seriesTitle.updateDefaultTimeline(0L, 90L, 1100L, now);
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1200L, now
            );
            entry.updateTimeline(ScreenTimelineMode.INHERIT, null, null, ScreenTimelineMode.INHERIT, null, now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            // Legacy command (updateTimeline = false) shortens duration to 1100 seconds (<= inherited outroStart 1100s)
            UpdateScreenEntryCommand legacyCmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1 Updated", null, 1100L
            );

            assertThatThrownBy(() -> useCase.execute(legacyCmd))
                    .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                    .hasMessage("Thời gian bắt đầu Outro phải nhỏ hơn thời lượng tập phim.");

            // Aggregate unmutated and never saved
            assertThat(entry.getDurationSeconds()).isEqualTo(1200L);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("UX02-C2-SR01 Defect 2 Regression: legacy command changes duration to another valid value successfully")
        void shouldAllowLegacyCommandWhenDurationChangedToAnotherValidValue() {
            seriesTitle.updateDefaultTimeline(0L, 90L, 1100L, now);
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1200L, now
            );
            entry.updateTimeline(ScreenTimelineMode.OVERRIDE, 15L, 105L, ScreenTimelineMode.INHERIT, null, now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(50));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            // Valid duration extension from 1200s to 1300s
            UpdateScreenEntryCommand legacyCmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1 Updated", null, 1300L
            );

            ScreenEntryDTO result = useCase.execute(legacyCmd);

            assertThat(result.durationSeconds()).isEqualTo(1300L);
            assertThat(result.introMode()).isEqualTo(ScreenTimelineMode.OVERRIDE);
            assertThat(result.introStartSeconds()).isEqualTo(15L);
            assertThat(result.introEndSeconds()).isEqualTo(105L);
            assertThat(result.outroMode()).isEqualTo(ScreenTimelineMode.INHERIT);
            verify(entryRepository).save(entry);
        }

        @Test
        @DisplayName("UX02-C2-SR01 Defect 1 Regression: rejects explicit timeline update when mode selector is null")
        void shouldRejectExplicitTimelineUpdateWhenModeSelectorIsNull() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));
            ScreenEntry entry = ScreenEntry.createDraft(
                    entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, null, null, 1200L, now
            );
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            UpdateScreenEntryUseCase useCase = new UpdateScreenEntryUseCase(titleRepository, entryRepository, clockPort);
            UpdateScreenEntryCommand cmd = new UpdateScreenEntryCommand(
                    seriesTitleId, entryId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", null, 1200L,
                    true, null, null, null, ScreenTimelineMode.INHERIT, null
            );

            assertThatThrownBy(() -> useCase.execute(cmd))
                    .isInstanceOf(InvalidScreenTimelineConfigurationException.class)
                    .hasMessage("Vui lòng chọn chế độ mốc thời gian cho cả Intro và Outro.");

            verify(entryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("PublishScreenEntryUseCase")
    class PublishTests {

        @Test
        @DisplayName("Should throw ScreenTitleNotFoundException when title does not exist")
        void shouldThrowWhenTitleNotFound() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.empty());

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ownership: Should reject publish request when entry belongs to foreign title")
        void shouldRejectForeignTitleOwnership() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            ScreenEntry foreignEntry = ScreenEntry.createDraft(entryId, foreignTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(foreignEntry));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenEntryNotFoundException.class);

            // Verify foreignEntry remains unchanged (still DRAFT, publishedAt null)
            assertThat(foreignEntry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
            assertThat(foreignEntry.getPublishedAt()).isNull();
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("First publication sets status to PUBLISHED and publishedAt from ClockPort")
        void shouldPublishDraftEntry() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            Instant publishTime = now.plusSeconds(500);
            when(clockPort.now()).thenReturn(publishTime);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);
            ScreenEntryDTO published = useCase.execute(seriesTitleId, entryId);

            assertThat(published.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(published.publishedAt()).isEqualTo(publishTime);
            assertThat(published.updatedAt()).isEqualTo(publishTime);
        }

        @Test
        @DisplayName("Should throw IllegalStateException when attempting to publish already published entry")
        void shouldRejectPublishWhenAlreadyPublished() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            entry.publish(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already published");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when attempting to publish archived entry")
        void shouldRejectPublishWhenArchived() {
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesTitle));

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            entry.archive(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Archived ScreenEntry cannot be published");
        }

        @Test
        @DisplayName("Publication against declared total: Case 1 — allows publishing episode number equal to declared total")
        void shouldAllowPublishingEpisodeEqualToDeclaredTotal() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry entry24 = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-24", ScreenEntryType.EPISODE, 24, "Ep 24", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry24));
            when(clockPort.now()).thenReturn(now.plusSeconds(100));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);
            ScreenEntryDTO published = useCase.execute(seriesTitleId, entryId);

            assertThat(published.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(published.number()).isEqualTo(24);
        }

        @Test
        @DisplayName("Publication against declared total: Case 2 — rejects publishing episode number exceeding declared total")
        void shouldRejectPublishingEpisodeExceedingDeclaredTotal() {
            ScreenTitle seriesWithTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithTotal));

            ScreenEntry entry25 = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-25", ScreenEntryType.EPISODE, 25, "Ep 25", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry25));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(InvalidScreenTitleTotalEpisodeCountException.class)
                    .hasMessageContaining("25")
                    .hasMessageContaining("24");
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Publication against declared total: Case 3 — allows publishing episode when total is unknown")
        void shouldAllowPublishingEpisodeWhenTotalIsUnknown() {
            ScreenTitle seriesWithoutTotal = ScreenTitle.createDraft(
                    seriesTitleId, "series-slug", "Series Title", null, null,
                    ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, null
            );
            when(titleRepository.findByIdForUpdate(seriesTitleId)).thenReturn(Optional.of(seriesWithoutTotal));

            ScreenEntry entry25 = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-25", ScreenEntryType.EPISODE, 25, "Ep 25", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry25));
            when(clockPort.now()).thenReturn(now.plusSeconds(100));
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);
            ScreenEntryDTO published = useCase.execute(seriesTitleId, entryId);

            assertThat(published.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            assertThat(published.number()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("ArchiveScreenEntryUseCase")
    class ArchiveTests {

        @Test
        @DisplayName("Should throw ScreenTitleNotFoundException when title does not exist")
        void shouldThrowWhenTitleNotFound() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(false);

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ownership: Should reject archive request when entry belongs to foreign title")
        void shouldRejectForeignTitleOwnership() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);

            ScreenEntry foreignEntry = ScreenEntry.createDraft(entryId, foreignTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(foreignEntry));

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(ScreenEntryNotFoundException.class);

            // Verify foreignEntry remains unchanged
            assertThat(foreignEntry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Archiving published entry sets status ARCHIVED and preserves publishedAt")
        void shouldArchivePublishedEntryAndPreservePublishedAt() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);

            Instant publishTime = now.plusSeconds(100);
            Instant archiveTime = now.plusSeconds(200);

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            entry.publish(publishTime);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

            when(clockPort.now()).thenReturn(archiveTime);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);
            ScreenEntryDTO archived = useCase.execute(seriesTitleId, entryId);

            assertThat(archived.status()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
            assertThat(archived.publishedAt()).isEqualTo(publishTime); // preserved exactly
            assertThat(archived.updatedAt()).isEqualTo(archiveTime);
        }

        @Test
        @DisplayName("Should throw IllegalStateException when attempting to archive already archived entry")
        void shouldRejectArchiveWhenAlreadyArchived() {
            when(titleRepository.existsById(seriesTitleId)).thenReturn(true);

            ScreenEntry entry = ScreenEntry.createDraft(entryId, seriesTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
            entry.archive(now.plusSeconds(100));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(now.plusSeconds(200));

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(titleRepository, entryRepository, mediaVisibilityPort, clockPort);

            assertThatThrownBy(() -> useCase.execute(seriesTitleId, entryId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already archived");
        }
    }
}
