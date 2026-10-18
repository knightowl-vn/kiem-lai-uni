package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReplaceScreenEntryVideoUseCase Unit Tests")
class ReplaceScreenEntryVideoUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    @Mock
    private ScreenMediaValidationPort mediaValidationPort;

    @Mock
    private ClockPort clockPort;

    private ReplaceScreenEntryVideoUseCase useCase;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID oldVideoId = UUID.randomUUID();
    private final UUID newVideoId = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-10-10T10:00:00Z");
    private final Instant publishedAt = Instant.parse("2026-10-12T08:00:00Z");
    private final Instant now = Instant.parse("2026-10-17T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ReplaceScreenEntryVideoUseCase(
                titleRepository,
                entryRepository,
                mediaValidationPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Replace: successfully replaces video on DRAFT entry with matching expected asset ID")
    void shouldSuccessfullyReplaceVideoOnDraftEntry() {
        ScreenEntry entry = createEntry(entryId, titleId, oldVideoId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(clockPort.now()).thenReturn(now);
        when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ScreenEntryDTO result = useCase.execute(titleId, entryId, newVideoId, oldVideoId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(newVideoId);
        assertThat(result.updatedAt()).isEqualTo(now);

        verify(mediaValidationPort).validateVideoMediaAsset(newVideoId);
        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository).save(entry);
    }

    @Test
    @DisplayName("Replace: successfully replaces video on PUBLISHED entry preserving status and publishedAt")
    void shouldSuccessfullyReplaceVideoOnPublishedEntryPreservingPublicationState() {
        ScreenEntry entry = createEntry(entryId, titleId, oldVideoId);
        entry.publish(publishedAt);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(clockPort.now()).thenReturn(now);
        when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ScreenEntryDTO result = useCase.execute(titleId, entryId, newVideoId, oldVideoId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(newVideoId);
        assertThat(result.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(result.publishedAt()).isEqualTo(publishedAt);
        assertThat(result.updatedAt()).isEqualTo(now);

        verify(mediaValidationPort).validateVideoMediaAsset(newVideoId);
        verify(entryRepository).save(entry);
    }

    @Test
    @DisplayName("Replace: idempotently returns current entry if identical new video is already attached")
    void shouldIdempotentlyReturnWhenIdenticalVideoAlreadyAttached() {
        ScreenEntry entry = createEntry(entryId, titleId, newVideoId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        ScreenEntryDTO result = useCase.execute(titleId, entryId, newVideoId, oldVideoId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(newVideoId);

        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails when title does not exist")
    void shouldFailWhenTitleNotFound() {
        when(titleRepository.existsById(titleId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, oldVideoId))
                .isInstanceOf(ScreenTitleNotFoundException.class);

        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails when entry does not exist")
    void shouldFailWhenEntryNotFound() {
        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, oldVideoId))
                .isInstanceOf(ScreenEntryNotFoundException.class);

        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails when entry belongs to different title")
    void shouldFailWhenTitleMismatch() {
        UUID otherTitleId = UUID.randomUUID();
        ScreenEntry entry = createEntry(entryId, otherTitleId, oldVideoId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, oldVideoId))
                .isInstanceOf(ScreenEntryNotFoundException.class);

        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails when entry has no attached video (must use attach instead)")
    void shouldFailWhenNoVideoAttached() {
        ScreenEntry entry = createEntry(entryId, titleId, null);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, oldVideoId))
                .isInstanceOf(ScreenEntryVideoNotAttachedException.class)
                .hasMessageContaining("chưa gắn video");

        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails with conflict when expected current video ID does not match")
    void shouldFailWhenExpectedCurrentVideoDoesNotMatch() {
        UUID unexpectedOldVideoId = UUID.randomUUID();
        ScreenEntry entry = createEntry(entryId, titleId, oldVideoId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, unexpectedOldVideoId))
                .isInstanceOf(ScreenEntryVideoConflictException.class)
                .hasMessageContaining("Xung đột");

        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails when expected current video ID is null (E2L1-02)")
    void shouldFailWhenExpectedCurrentVideoIsNull() {
        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Expected current video MediaAsset ID cannot be null");

        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).validateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Replace: fails if media validation rejects candidate video asset")
    void shouldFailWhenMediaValidationRejects() {
        ScreenEntry entry = createEntry(entryId, titleId, oldVideoId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        doThrow(new IllegalArgumentException("Invalid media asset type"))
                .when(mediaValidationPort).validateVideoMediaAsset(newVideoId);

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, newVideoId, oldVideoId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid media asset type");

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Concurrency: two concurrent replacements for same expected ID cannot both win (second fails with conflict)")
    void shouldPreventTwoConcurrentStaleReplacementsFromBothWinning() {
        ScreenEntry entry = createEntry(entryId, titleId, oldVideoId);
        UUID replacementVideo1 = UUID.randomUUID();
        UUID replacementVideo2 = UUID.randomUUID();

        when(titleRepository.existsById(titleId)).thenReturn(true);
        // First execution: entry has oldVideoId
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(clockPort.now()).thenReturn(now);
        when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Winner 1 replaces oldVideoId with replacementVideo1
        ScreenEntryDTO result1 = useCase.execute(titleId, entryId, replacementVideo1, oldVideoId);
        assertThat(result1.videoMediaAssetId()).isEqualTo(replacementVideo1);

        // Winner 1 modified the entry in-place; now entry has replacementVideo1
        assertThat(entry.getVideoMediaAssetId()).isEqualTo(replacementVideo1);

        // Competitor 2 now attempts to replace with stale expected ID oldVideoId
        assertThatThrownBy(() -> useCase.execute(titleId, entryId, replacementVideo2, oldVideoId))
                .isInstanceOf(ScreenEntryVideoConflictException.class)
                .hasMessageContaining("Xung đột");

        // Verify entry was not overwritten with replacementVideo2
        assertThat(entry.getVideoMediaAssetId()).isEqualTo(replacementVideo1);
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
