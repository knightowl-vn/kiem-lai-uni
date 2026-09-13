package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterNarrationManifestRepositoryPort;
import com.universe.novel.domain.narration.ChapterNarrationManifest;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SynchronizePublishedChapterNarrationUseCase Unit Tests")
class SynchronizePublishedChapterNarrationUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Instant T0 = Instant.parse("2026-09-06T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-06T11:00:00Z");

    @Mock
    private ReconcileChapterNarrationSegmentsUseCase reconcileSegmentsUseCase;

    @Mock
    private ChapterNarrationManifestRepositoryPort manifestRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private SynchronizePublishedChapterNarrationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SynchronizePublishedChapterNarrationUseCase(
                reconcileSegmentsUseCase,
                manifestRepositoryPort,
                clockPort
        );
    }

    private ReconcileChapterNarrationSegmentsResult sampleResult(long version, String hash) {
        return new ReconcileChapterNarrationSegmentsResult(
                version,
                hash,
                List.of(new ChapterNarrationSegmentReconciliationItem(
                        UUID.randomUUID(), 0, ChapterNarrationSegmentReconciliationDisposition.REUSED_UNCHANGED
                )),
                List.of()
        );
    }

    @Test
    @DisplayName("Should create and save new manifest when manifest is missing")
    void shouldCreateAndSaveNewManifestWhenMissing() {
        ReconcileChapterNarrationSegmentsResult reconciliationResult = sampleResult(1L, HASH_A);
        when(reconcileSegmentsUseCase.execute(CHAPTER_ID)).thenReturn(reconciliationResult);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(T0);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result).isSameAs(reconciliationResult);

        InOrder inOrder = inOrder(reconcileSegmentsUseCase, manifestRepositoryPort);
        inOrder.verify(reconcileSegmentsUseCase).execute(CHAPTER_ID);
        inOrder.verify(manifestRepositoryPort).findByChapterId(CHAPTER_ID);

        ArgumentCaptor<ChapterNarrationManifest> captor = ArgumentCaptor.forClass(ChapterNarrationManifest.class);
        inOrder.verify(manifestRepositoryPort).save(captor.capture());

        ChapterNarrationManifest saved = captor.getValue();
        assertThat(saved.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(saved.getSourceContentVersion()).isEqualTo(1L);
        assertThat(saved.getManifestHash()).isEqualTo(HASH_A);
        assertThat(saved.getCreatedAt()).isEqualTo(T0);
        assertThat(saved.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Should perform true no-op when existing manifest is identical")
    void shouldNoOpWhenExistingManifestIsIdentical() {
        ReconcileChapterNarrationSegmentsResult reconciliationResult = sampleResult(2L, HASH_A);
        ChapterNarrationManifest existingManifest = ChapterNarrationManifest.create(CHAPTER_ID, 2L, HASH_A, T0);

        when(reconcileSegmentsUseCase.execute(CHAPTER_ID)).thenReturn(reconciliationResult);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(existingManifest));

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result).isSameAs(reconciliationResult);

        InOrder inOrder = inOrder(reconcileSegmentsUseCase, manifestRepositoryPort);
        inOrder.verify(reconcileSegmentsUseCase).execute(CHAPTER_ID);
        inOrder.verify(manifestRepositoryPort).findByChapterId(CHAPTER_ID);

        verify(manifestRepositoryPort, never()).save(any());
        verifyNoInteractions(clockPort);

        assertThat(existingManifest.getSourceContentVersion()).isEqualTo(2L);
        assertThat(existingManifest.getManifestHash()).isEqualTo(HASH_A);
        assertThat(existingManifest.getCreatedAt()).isEqualTo(T0);
        assertThat(existingManifest.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Should reconcile and save manifest when source version is higher")
    void shouldReconcileAndSaveWhenSourceVersionIsHigher() {
        ReconcileChapterNarrationSegmentsResult reconciliationResult = sampleResult(2L, HASH_B);
        ChapterNarrationManifest existingManifest = ChapterNarrationManifest.create(CHAPTER_ID, 1L, HASH_A, T0);

        when(reconcileSegmentsUseCase.execute(CHAPTER_ID)).thenReturn(reconciliationResult);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(existingManifest));
        when(clockPort.now()).thenReturn(T1);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result).isSameAs(reconciliationResult);

        InOrder inOrder = inOrder(reconcileSegmentsUseCase, manifestRepositoryPort);
        inOrder.verify(reconcileSegmentsUseCase).execute(CHAPTER_ID);
        inOrder.verify(manifestRepositoryPort).findByChapterId(CHAPTER_ID);
        inOrder.verify(manifestRepositoryPort).save(existingManifest);

        assertThat(existingManifest.getSourceContentVersion()).isEqualTo(2L);
        assertThat(existingManifest.getManifestHash()).isEqualTo(HASH_B);
        assertThat(existingManifest.getCreatedAt()).isEqualTo(T0);
        assertThat(existingManifest.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Should reconcile and save manifest when same source version has different manifestHash")
    void shouldReconcileAndSaveWhenSameVersionHasDifferentHash() {
        ReconcileChapterNarrationSegmentsResult reconciliationResult = sampleResult(1L, HASH_B);
        ChapterNarrationManifest existingManifest = ChapterNarrationManifest.create(CHAPTER_ID, 1L, HASH_A, T0);

        when(reconcileSegmentsUseCase.execute(CHAPTER_ID)).thenReturn(reconciliationResult);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(existingManifest));
        when(clockPort.now()).thenReturn(T1);

        ReconcileChapterNarrationSegmentsResult result = useCase.execute(CHAPTER_ID);

        assertThat(result).isSameAs(reconciliationResult);

        InOrder inOrder = inOrder(reconcileSegmentsUseCase, manifestRepositoryPort);
        inOrder.verify(reconcileSegmentsUseCase).execute(CHAPTER_ID);
        inOrder.verify(manifestRepositoryPort).findByChapterId(CHAPTER_ID);
        inOrder.verify(manifestRepositoryPort).save(existingManifest);

        assertThat(existingManifest.getSourceContentVersion()).isEqualTo(1L);
        assertThat(existingManifest.getManifestHash()).isEqualTo(HASH_B);
        assertThat(existingManifest.getCreatedAt()).isEqualTo(T0);
        assertThat(existingManifest.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Should propagate exception and not save when reconciliation result has backward version")
    void shouldPropagateExceptionWhenBackwardVersion() {
        ReconcileChapterNarrationSegmentsResult reconciliationResult = sampleResult(2L, HASH_B);
        ChapterNarrationManifest existingManifest = ChapterNarrationManifest.create(CHAPTER_ID, 3L, HASH_A, T0);

        when(reconcileSegmentsUseCase.execute(CHAPTER_ID)).thenReturn(reconciliationResult);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.of(existingManifest));
        when(clockPort.now()).thenReturn(T1);

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot reconcile manifest to an earlier source content version");

        verify(manifestRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should propagate exception and never query or save manifest when reconciliation fails")
    void shouldPropagateWhenReconciliationFails() {
        when(reconcileSegmentsUseCase.execute(CHAPTER_ID))
                .thenThrow(new ChapterNotFoundException(CHAPTER_ID));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID))
                .isInstanceOf(ChapterNotFoundException.class);

        verifyNoInteractions(manifestRepositoryPort);
        verifyNoInteractions(clockPort);
    }

    @Test
    @DisplayName("Should propagate exception when manifest repository save fails")
    void shouldPropagateWhenManifestSaveFails() {
        ReconcileChapterNarrationSegmentsResult reconciliationResult = sampleResult(1L, HASH_A);
        when(reconcileSegmentsUseCase.execute(CHAPTER_ID)).thenReturn(reconciliationResult);
        when(manifestRepositoryPort.findByChapterId(CHAPTER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(T0);
        when(manifestRepositoryPort.save(any())).thenThrow(new RuntimeException("Database error during manifest save"));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error during manifest save");
    }

    @Test
    @DisplayName("Should reject null chapterId before invoking dependencies")
    void shouldRejectNullChapterId() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chapterId must not be null");

        verifyNoInteractions(reconcileSegmentsUseCase);
        verifyNoInteractions(manifestRepositoryPort);
        verifyNoInteractions(clockPort);
    }
}
