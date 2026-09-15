package com.universe.novel.application.narration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChapterNarrationExecutionCoordinator Unit Tests (H.10A1)")
class ChapterNarrationExecutionCoordinatorTest {

    private static final UUID CHAPTER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHAPTER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VOICE_1 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VOICE_2 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private ChapterNarrationExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ChapterNarrationExecutionCoordinator();
    }

    @Test
    @DisplayName("1. First acquire for a chapter+voice pair succeeds and marks in-flight")
    void firstAcquireSucceeds() {
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isFalse();

        boolean acquired = coordinator.tryAcquire(CHAPTER_A, VOICE_1);

        assertThat(acquired).isTrue();
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isTrue();
    }

    @Test
    @DisplayName("2. Duplicate acquire for the same chapter+voice pair fails")
    void duplicateSameKeyFails() {
        boolean first = coordinator.tryAcquire(CHAPTER_A, VOICE_1);
        assertThat(first).isTrue();

        boolean second = coordinator.tryAcquire(CHAPTER_A, VOICE_1);
        assertThat(second).isFalse();
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isTrue();
    }

    @Test
    @DisplayName("3. Different chapter or voice keys succeed independently")
    void differentKeysSucceedIndependently() {
        boolean chA_v1 = coordinator.tryAcquire(CHAPTER_A, VOICE_1);
        boolean chA_v2 = coordinator.tryAcquire(CHAPTER_A, VOICE_2);
        boolean chB_v1 = coordinator.tryAcquire(CHAPTER_B, VOICE_1);

        assertThat(chA_v1).isTrue();
        assertThat(chA_v2).isTrue();
        assertThat(chB_v1).isTrue();

        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isTrue();
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_2)).isTrue();
        assertThat(coordinator.isInFlight(CHAPTER_B, VOICE_1)).isTrue();
        assertThat(coordinator.isInFlight(CHAPTER_B, VOICE_2)).isFalse();
    }

    @Test
    @DisplayName("4. Release permits subsequent reacquisition of the same key")
    void releasePermitsReacquisition() {
        coordinator.tryAcquire(CHAPTER_A, VOICE_1);
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isTrue();

        coordinator.release(CHAPTER_A, VOICE_1);
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isFalse();

        boolean reacquired = coordinator.tryAcquire(CHAPTER_A, VOICE_1);
        assertThat(reacquired).isTrue();
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isTrue();
    }

    @Test
    @DisplayName("5. Releasing unacquired key is a safe no-op")
    void releaseUnacquiredKeyIsSafeNoOp() {
        coordinator.release(CHAPTER_A, VOICE_1);
        assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isFalse();
    }

    @Test
    @DisplayName("6. Null arguments are rejected across all methods")
    void nullArgumentsRejected() {
        assertThatThrownBy(() -> coordinator.tryAcquire(null, VOICE_1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> coordinator.tryAcquire(CHAPTER_A, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> coordinator.release(null, VOICE_1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> coordinator.release(CHAPTER_A, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> coordinator.isInFlight(null, VOICE_1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> coordinator.isInFlight(CHAPTER_A, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("7. Concurrent acquisition by multiple threads results in exactly one winner")
    void concurrentAcquisitionHasSingleWinner() throws InterruptedException, ExecutionException {
        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return coordinator.tryAcquire(CHAPTER_A, VOICE_1);
                });
            }

            startGate.countDown();
            List<Future<Boolean>> results = pool.invokeAll(tasks);

            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }

            assertThat(winners).isEqualTo(1);
            assertThat(coordinator.isInFlight(CHAPTER_A, VOICE_1)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
