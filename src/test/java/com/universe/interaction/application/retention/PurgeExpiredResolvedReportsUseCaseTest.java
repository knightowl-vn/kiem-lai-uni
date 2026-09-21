package com.universe.interaction.application.retention;

import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeExpiredResolvedReportsUseCaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-21T12:00:00Z");

    @Mock
    private ClockPort clockPort;

    @Mock
    private InteractionReportRepositoryPort repository;

    @Test
    @DisplayName("A-D & F: Cutoff is computed exactly once from ClockPort, passed to repository, and deleted count returned without looping")
    void shouldComputeCutoffOnceAndPurgeSingleBatch() {
        when(clockPort.now()).thenReturn(FIXED_NOW);
        when(repository.purgeExpiredResolvedBefore(any(Instant.class), anyInt())).thenReturn(7);

        PurgeExpiredResolvedReportsUseCase useCase = new PurgeExpiredResolvedReportsUseCase(clockPort, repository);

        int deletedCount = useCase.execute(50);

        assertThat(deletedCount).isEqualTo(7);

        // A. ClockPort used exactly once per execution
        verify(clockPort, times(1)).now();

        // B & C. Cutoff is exactly now - Duration.ofDays(30) and batchSize is 50
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(repository, times(1)).purgeExpiredResolvedBefore(cutoffCaptor.capture(), limitCaptor.capture());

        Instant expectedCutoff = FIXED_NOW.minus(Duration.ofDays(30));
        assertThat(cutoffCaptor.getValue()).isEqualTo(expectedCutoff);
        assertThat(limitCaptor.getValue()).isEqualTo(50);

        // F. No retry or loop
        verify(repository, times(1)).purgeExpiredResolvedBefore(any(), anyInt());
    }

    @Test
    @DisplayName("E: batchSize <= 0 rejected with IllegalArgumentException before invoking repository")
    void shouldRejectNonPositiveBatchSize() {
        PurgeExpiredResolvedReportsUseCase useCase = new PurgeExpiredResolvedReportsUseCase(clockPort, repository);

        assertThatThrownBy(() -> useCase.execute(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        assertThatThrownBy(() -> useCase.execute(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch size must be greater than 0");

        verify(clockPort, never()).now();
        verify(repository, never()).purgeExpiredResolvedBefore(any(), anyInt());
    }

    @Test
    @DisplayName("Constructor rejects null dependencies")
    void shouldRejectNullDependencies() {
        assertThatThrownBy(() -> new PurgeExpiredResolvedReportsUseCase(null, repository))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ClockPort cannot be null");

        assertThatThrownBy(() -> new PurgeExpiredResolvedReportsUseCase(clockPort, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("InteractionReportRepositoryPort cannot be null");
    }
}
