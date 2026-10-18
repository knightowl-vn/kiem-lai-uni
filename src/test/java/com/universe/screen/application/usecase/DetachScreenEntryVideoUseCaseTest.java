package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DetachScreenEntryVideoUseCase Unit Tests")
class DetachScreenEntryVideoUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    @Mock
    private ClockPort clockPort;

    private DetachScreenEntryVideoUseCase useCase;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID videoAssetId = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-10-10T10:00:00Z");
    private final Instant now = Instant.parse("2026-10-17T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new DetachScreenEntryVideoUseCase(
                titleRepository,
                entryRepository,
                clockPort
        );
    }

    @Test
    @DisplayName("Detach: successfully detaches video from DRAFT entry with matching expected asset ID")
    void shouldSuccessfullyDetachVideoFromDraftEntry() {
        ScreenEntry entry = createEntry(entryId, titleId, videoAssetId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(clockPort.now()).thenReturn(now);
        when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ScreenEntryDTO result = useCase.execute(titleId, entryId, videoAssetId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isNull();
        assertThat(result.updatedAt()).isEqualTo(now);

        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository).save(entry);
    }

    @Test
    @DisplayName("Detach: fails when expected current asset ID is null (E2L1-02)")
    void shouldFailWhenExpectedIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(titleId, entryId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Expected video MediaAsset ID cannot be null");

        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Detach: fails when title does not exist")
    void shouldFailWhenTitleNotFound() {
        when(titleRepository.existsById(titleId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenTitleNotFoundException.class);

        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Detach: fails when entry does not exist")
    void shouldFailWhenEntryNotFound() {
        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenEntryNotFoundException.class);

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Detach: fails when entry belongs to a different title")
    void shouldFailWhenTitleMismatch() {
        UUID otherTitleId = UUID.randomUUID();
        ScreenEntry entry = createEntry(entryId, otherTitleId, videoAssetId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenEntryNotFoundException.class);

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Detach: fails when entry has no attached video")
    void shouldFailWhenNoVideoAttached() {
        ScreenEntry entry = createEntry(entryId, titleId, null);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenEntryVideoNotAttachedException.class)
                .hasMessageContaining("chưa gắn video");

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Detach: fails with conflict when expected current video ID does not match")
    void shouldFailWhenExpectedVideoDoesNotMatch() {
        UUID differentVideoId = UUID.randomUUID();
        ScreenEntry entry = createEntry(entryId, titleId, videoAssetId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, differentVideoId))
                .isInstanceOf(ScreenEntryVideoConflictException.class)
                .hasMessageContaining("Xung đột");

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Detach: rejects detaching video from PUBLISHED entry")
    void shouldRejectDetachingVideoFromPublishedEntry() {
        ScreenEntry entry = createEntry(entryId, titleId, videoAssetId);
        entry.publish(t0.plusSeconds(1800));

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");

        verify(entryRepository, never()).save(any());
    }

    private ScreenEntry createEntry(UUID id, UUID tId, UUID currentVideoId) {
        return ScreenEntry.createDraft(
                id,
                tId,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1",
                "Tóm tắt tập 1",
                currentVideoId,
                null,
                1200L,
                t0
        );
    }
}
