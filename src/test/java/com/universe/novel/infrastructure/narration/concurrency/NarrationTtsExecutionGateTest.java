package com.universe.novel.infrastructure.narration.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NarrationTtsExecutionGate Unit & Spring Context Tests (H.10A2)")
class NarrationTtsExecutionGateTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NarrationTtsExecutionGate.class);

    // ==========================================
    // A. GATE UNIT TESTS
    // ==========================================

    @Test
    @DisplayName("1. maxConcurrentTts=1: first execution enters, second concurrent execution blocks until first releases")
    void firstExecutionEntersSecondBlocksUntilRelease() throws Exception {
        NarrationTtsExecutionGate gate = new NarrationTtsExecutionGate(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicBoolean secondEnteredWhileFirstRunning = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            gate.execute(() -> {
                firstEntered.countDown();
                allowFirstToFinish.await(5, TimeUnit.SECONDS);
                return "first";
            });
        });

        Thread t2 = new Thread(() -> {
            try {
                firstEntered.await(5, TimeUnit.SECONDS);
                secondStarted.countDown();
                gate.execute(() -> {
                    secondEnteredWhileFirstRunning.set(allowFirstToFinish.getCount() > 0);
                    return "second";
                });
            } catch (InterruptedException ignored) {
            }
        });

        t1.start();
        t2.start();

        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // While t1 is holding the permit, available permit is 0
        assertThat(gate.getAvailablePermits()).isEqualTo(0);
        assertThat(secondEnteredWhileFirstRunning.get()).isFalse();

        // Release t1
        allowFirstToFinish.countDown();
        t1.join(5000);
        t2.join(5000);

        assertThat(secondEnteredWhileFirstRunning.get()).isFalse();
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. After first execution completes: second execution proceeds and completes")
    void secondExecutionProceedsAfterFirstCompletes() {
        NarrationTtsExecutionGate gate = new NarrationTtsExecutionGate(1);

        String result1 = gate.execute(() -> "first-done");
        assertThat(result1).isEqualTo("first-done");
        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        String result2 = gate.execute(() -> "second-done");
        assertThat(result2).isEqualTo("second-done");
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. If guarded action throws RuntimeException: permit is still released and next caller can enter")
    void permitReleasedWhenGuardedActionThrows() {
        NarrationTtsExecutionGate gate = new NarrationTtsExecutionGate(1);

        assertThatThrownBy(() -> gate.execute(() -> {
            throw new IllegalStateException("Action failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Action failure");

        // Permit must be released
        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        String recovered = gate.execute(() -> "next-caller-success");
        assertThat(recovered).isEqualTo("next-caller-success");
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. Multiple different callers share the SAME capacity")
    void multipleDifferentCallersShareSameCapacity() throws Exception {
        NarrationTtsExecutionGate gate = new NarrationTtsExecutionGate(2);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch allowBothToFinish = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<String> f1 = pool.submit(() -> gate.execute(() -> {
                bothEntered.countDown();
                allowBothToFinish.await(5, TimeUnit.SECONDS);
                return "worker-1";
            }));

            Future<String> f2 = pool.submit(() -> gate.execute(() -> {
                bothEntered.countDown();
                allowBothToFinish.await(5, TimeUnit.SECONDS);
                return "worker-2";
            }));

            assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.getAvailablePermits()).isEqualTo(0);

            // Third caller attempts entry and is blocked
            CountDownLatch thirdStarted = new CountDownLatch(1);
            AtomicBoolean thirdEnteredEarly = new AtomicBoolean(false);

            Future<String> f3 = pool.submit(() -> {
                thirdStarted.countDown();
                return gate.execute(() -> {
                    thirdEnteredEarly.set(allowBothToFinish.getCount() > 0);
                    return "worker-3";
                });
            });

            assertThat(thirdStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thirdEnteredEarly.get()).isFalse();

            // Release both
            allowBothToFinish.countDown();

            assertThat(f1.get(5, TimeUnit.SECONDS)).isEqualTo("worker-1");
            assertThat(f2.get(5, TimeUnit.SECONDS)).isEqualTo("worker-2");
            assertThat(f3.get(5, TimeUnit.SECONDS)).isEqualTo("worker-3");
            assertThat(thirdEnteredEarly.get()).isFalse();
            assertThat(gate.getAvailablePermits()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("5. null action rejected with IllegalArgumentException")
    void nullActionRejected() {
        NarrationTtsExecutionGate gate = new NarrationTtsExecutionGate(1);

        assertThatThrownBy(() -> gate.execute(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Action must not be null");
    }

    @Test
    @DisplayName("6. invalid maxConcurrentTts values (< 1) rejected")
    void invalidMaxConcurrentTtsRejected() {
        assertThatThrownBy(() -> new NarrationTtsExecutionGate(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("novel.narration.concurrency.max-concurrent-tts must be >= 1");

        assertThatThrownBy(() -> new NarrationTtsExecutionGate(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("novel.narration.concurrency.max-concurrent-tts must be >= 1");
    }

    @Test
    @DisplayName("7. Interruption while waiting preserves interrupt status and does not leak or corrupt permits")
    void interruptionWhileWaitingPreservesStatusAndDoesNotLeakPermits() throws Exception {
        NarrationTtsExecutionGate gate = new NarrationTtsExecutionGate(1);
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch allowHolderToFinish = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            gate.execute(() -> {
                holderEntered.countDown();
                allowHolderToFinish.await(5, TimeUnit.SECONDS);
                return "held";
            });
        });
        holder.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.getAvailablePermits()).isEqualTo(0);

        AtomicBoolean interruptedStatusObserved = new AtomicBoolean(false);
        AtomicBoolean exceptionObserved = new AtomicBoolean(false);
        CountDownLatch waiterQueued = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            waiterQueued.countDown();
            try {
                gate.execute(() -> "should not execute");
            } catch (IllegalStateException ex) {
                exceptionObserved.set(true);
                if (Thread.currentThread().isInterrupted()) {
                    interruptedStatusObserved.set(true);
                }
            }
        });
        waiter.start();
        assertThat(waiterQueued.await(5, TimeUnit.SECONDS)).isTrue();

        // Wait until waiter is queued for the permit
        while (gate.getQueueLength() == 0) {
            Thread.sleep(10);
        }

        // Interrupt waiter
        waiter.interrupt();
        waiter.join(5000);

        assertThat(exceptionObserved.get()).isTrue();
        assertThat(interruptedStatusObserved.get()).isTrue();
        // Permits must still be 0 (held by holder, not leaked or double-released)
        assertThat(gate.getAvailablePermits()).isEqualTo(0);

        // Now release holder
        allowHolderToFinish.countDown();
        holder.join(5000);

        // Permit count is cleanly restored to 1
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
    }

    // ==========================================
    // B. SPRING CONFIGURATION TESTS
    // ==========================================

    @Test
    @DisplayName("8. Spring context creates gate bean with default maxConcurrentTts=1")
    void springContextCreatesGateWithDefaultProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NarrationTtsExecutionGate.class);
            NarrationTtsExecutionGate gate = context.getBean(NarrationTtsExecutionGate.class);
            assertThat(gate.getMaxConcurrentTts()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("9. Spring context honors overridden maxConcurrentTts=2")
    void springContextHonorsOverriddenProperties() {
        contextRunner
                .withPropertyValues("novel.narration.concurrency.max-concurrent-tts=2")
                .run(context -> {
                    assertThat(context).hasSingleBean(NarrationTtsExecutionGate.class);
                    NarrationTtsExecutionGate gate = context.getBean(NarrationTtsExecutionGate.class);
                    assertThat(gate.getMaxConcurrentTts()).isEqualTo(2);
                    assertThat(gate.getAvailablePermits()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("10. Spring context fails startup when maxConcurrentTts=0")
    void springContextFailsStartupWhenInvalid() {
        contextRunner
                .withPropertyValues("novel.narration.concurrency.max-concurrent-tts=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("novel.narration.concurrency.max-concurrent-tts must be >= 1, but found: 0");
                });
    }
}
