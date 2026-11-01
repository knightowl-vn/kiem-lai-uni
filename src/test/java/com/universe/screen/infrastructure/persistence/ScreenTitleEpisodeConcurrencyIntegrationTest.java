package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.exceptions.InvalidScreenTitleTotalEpisodeCountException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        ScreenTitlePersistenceAdapter.class,
        ScreenEntryPersistenceAdapter.class,
        UpdateScreenTitleUseCase.class,
        UpdateScreenEntryUseCase.class,
        ScreenTitleEpisodeConcurrencyIntegrationTest.TestConfig.class
})
@DisplayName("ScreenTitle & ScreenEntry Real MySQL Concurrency Integration Tests")
class ScreenTitleEpisodeConcurrencyIntegrationTest {

    @MockBean
    private ScreenMediaValidationPort mediaValidationPort;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static final UUID TITLE_ID = UUID.fromString("a1111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY_ID = UUID.fromString("b2222222-2222-2222-2222-222222222222");
    private static final Instant BASE_TIME = Instant.parse("2026-10-10T12:00:00Z");

    private final ScreenTitleRepositoryPort titleRepositoryPort;
    private final ScreenEntryRepositoryPort entryRepositoryPort;
    private final UpdateScreenTitleUseCase updateScreenTitleUseCase;
    private final UpdateScreenEntryUseCase updateScreenEntryUseCase;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ControllableClock clock;

    @Autowired
    ScreenTitleEpisodeConcurrencyIntegrationTest(
            ScreenTitleRepositoryPort titleRepositoryPort,
            ScreenEntryRepositoryPort entryRepositoryPort,
            UpdateScreenTitleUseCase updateScreenTitleUseCase,
            UpdateScreenEntryUseCase updateScreenEntryUseCase,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ControllableClock clock
    ) {
        this.titleRepositoryPort = titleRepositoryPort;
        this.entryRepositoryPort = entryRepositoryPort;
        this.updateScreenTitleUseCase = updateScreenTitleUseCase;
        this.updateScreenEntryUseCase = updateScreenEntryUseCase;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @BeforeEach
    void setUp() {
        cleanupTestRows();
        clock.reset();
        seedPublishedTitleAndPublishedEpisode();
    }

    @AfterEach
    void tearDown() {
        clock.release();
        cleanupTestRows();
    }

    @Test
    @DisplayName("Concurrency Order 1: Title update wins lock -> blocks concurrent episode update -> episode update fails with total exceeded")
    void shouldPreventEpisodeUpdateExceedingTotalWhenTitleUpdateWinsLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Title thread will lock ScreenTitle, see max episode = 20, validate total = 24 >= 20, then pause at clockPort.now()
            clock.blockThread("title-thread");

            UpdateScreenTitleCommand titleCommand = new UpdateScreenTitleCommand(
                    TITLE_ID,
                    "concurrency-series-slug",
                    "Concurrency Series",
                    null,
                    "Synopsis",
                    ScreenStructure.SERIES,
                    ScreenPresentation.ANIMATION_3D,
                    null,
                    null,
                    24
            );

            Future<?> titleFuture = executor.submit(namedTask("title-thread", () -> updateScreenTitleUseCase.execute(titleCommand)));

            assertThat(clock.awaitBlocked(5, TimeUnit.SECONDS)).isTrue();

            // Entry thread attempts to update episode 20 -> 25.
            // It calls titleRepository.findByIdForUpdate(TITLE_ID), which must BLOCK at MySQL row lock on ScreenTitle!
            UpdateScreenEntryCommand entryCommand = new UpdateScreenEntryCommand(
                    TITLE_ID,
                    ENTRY_ID,
                    "concurrency-ep-25",
                    ScreenEntryType.EPISODE,
                    25,
                    "Episode 25",
                    null,
                    null
            );

            Future<?> entryFuture = executor.submit(namedTask("entry-thread", () -> updateScreenEntryUseCase.execute(entryCommand)));

            Thread.sleep(300);
            assertThat(entryFuture.isDone()).isFalse();

            // Release title thread to commit totalEpisodeCount = 24
            clock.release();

            titleFuture.get(5, TimeUnit.SECONDS);

            // Entry thread unblocks, acquires ScreenTitle lock, sees committed total = 24, and fails validation (25 > 24)
            assertThatThrownBy(() -> entryFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidScreenTitleTotalEpisodeCountException.class);

            // Verify final DB state: title totalEpisodeCount is 24, episode number remains 20
            Integer dbTotal = jdbcTemplate.queryForObject(
                    "SELECT total_episode_count FROM screen_titles WHERE id = ?",
                    Integer.class,
                    TITLE_ID.toString()
            );
            Integer dbEpNumber = jdbcTemplate.queryForObject(
                    "SELECT number FROM screen_entries WHERE id = ?",
                    Integer.class,
                    ENTRY_ID.toString()
            );

            assertThat(dbTotal).isEqualTo(24);
            assertThat(dbEpNumber).isEqualTo(20);

        } finally {
            clock.release();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Concurrency Order 2: Episode update wins lock -> blocks concurrent title update -> title update fails with latest episode exceeded")
    void shouldPreventTitleUpdateBelowEpisodeWhenEpisodeUpdateWinsLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Entry thread will lock ScreenTitle, lock ScreenEntry, validate total is null (so 25 is allowed), then pause at clockPort.now()
            clock.blockThread("entry-thread");

            UpdateScreenEntryCommand entryCommand = new UpdateScreenEntryCommand(
                    TITLE_ID,
                    ENTRY_ID,
                    "concurrency-ep-25",
                    ScreenEntryType.EPISODE,
                    25,
                    "Episode 25",
                    null,
                    null
            );

            Future<?> entryFuture = executor.submit(namedTask("entry-thread", () -> updateScreenEntryUseCase.execute(entryCommand)));

            assertThat(clock.awaitBlocked(5, TimeUnit.SECONDS)).isTrue();

            // Title thread attempts to set totalEpisodeCount = 24.
            // It calls titleRepository.findByIdForUpdate(TITLE_ID), which must BLOCK at MySQL row lock on ScreenTitle!
            UpdateScreenTitleCommand titleCommand = new UpdateScreenTitleCommand(
                    TITLE_ID,
                    "concurrency-series-slug",
                    "Concurrency Series",
                    null,
                    "Synopsis",
                    ScreenStructure.SERIES,
                    ScreenPresentation.ANIMATION_3D,
                    null,
                    null,
                    24
            );

            Future<?> titleFuture = executor.submit(namedTask("title-thread", () -> updateScreenTitleUseCase.execute(titleCommand)));

            Thread.sleep(300);
            assertThat(titleFuture.isDone()).isFalse();

            // Release entry thread to commit episode number = 25
            clock.release();

            entryFuture.get(5, TimeUnit.SECONDS);

            // Title thread unblocks, acquires ScreenTitle lock, queries latest published episode (25), and fails validation (24 < 25)
            assertThatThrownBy(() -> titleFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidScreenTitleTotalEpisodeCountException.class);

            // Verify final DB state: title totalEpisodeCount is still null (rolled back), episode number is 25
            Integer dbTotal = jdbcTemplate.queryForObject(
                    "SELECT total_episode_count FROM screen_titles WHERE id = ?",
                    Integer.class,
                    TITLE_ID.toString()
            );
            Integer dbEpNumber = jdbcTemplate.queryForObject(
                    "SELECT number FROM screen_entries WHERE id = ?",
                    Integer.class,
                    ENTRY_ID.toString()
            );

            assertThat(dbTotal).isNull();
            assertThat(dbEpNumber).isEqualTo(25);

        } finally {
            clock.release();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void seedPublishedTitleAndPublishedEpisode() {
        transactionTemplate.execute(status -> {
            ScreenTitle title = ScreenTitle.createDraft(
                    TITLE_ID,
                    "concurrency-series-slug",
                    "Concurrency Series",
                    ScreenStructure.SERIES,
                    ScreenPresentation.ANIMATION_3D,
                    BASE_TIME
            );
            title.publish(BASE_TIME);
            titleRepositoryPort.save(title);

            ScreenEntry entry = ScreenEntry.createDraft(
                    ENTRY_ID,
                    TITLE_ID,
                    "concurrency-ep-20",
                    ScreenEntryType.EPISODE,
                    20,
                    "Episode 20",
                    BASE_TIME
            );
            entry.publish(BASE_TIME);
            entryRepositoryPort.save(entry);

            return null;
        });
    }

    private void cleanupTestRows() {
        jdbcTemplate.update("DELETE FROM screen_entries WHERE title_id = ?", TITLE_ID.toString());
        jdbcTemplate.update("DELETE FROM screen_titles WHERE id = ?", TITLE_ID.toString());
    }

    private static <T> Callable<T> namedTask(String name, Callable<T> task) {
        return () -> {
            String originalName = Thread.currentThread().getName();
            Thread.currentThread().setName(name);
            try {
                return task.call();
            } finally {
                Thread.currentThread().setName(originalName);
            }
        };
    }

    static final class ControllableClock implements ClockPort {

        private final Instant fixedInstant;
        private volatile String blockedThreadName;
        private volatile CountDownLatch reachedLatch;
        private volatile CountDownLatch releaseLatch;

        ControllableClock(Instant fixedInstant) {
            this.fixedInstant = fixedInstant;
            reset();
        }

        void blockThread(String threadName) {
            this.blockedThreadName = threadName;
            this.reachedLatch = new CountDownLatch(1);
            this.releaseLatch = new CountDownLatch(1);
        }

        boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return reachedLatch.await(timeout, unit);
        }

        void release() {
            if (releaseLatch != null) {
                releaseLatch.countDown();
            }
        }

        void reset() {
            this.blockedThreadName = null;
            this.reachedLatch = new CountDownLatch(1);
            this.releaseLatch = new CountDownLatch(0);
        }

        @Override
        public Instant now() {
            String targetThread = blockedThreadName;
            if (targetThread != null && targetThread.equals(Thread.currentThread().getName())) {
                reachedLatch.countDown();
                try {
                    boolean released = releaseLatch.await(10, TimeUnit.SECONDS);
                    if (!released) {
                        throw new IllegalStateException("Timeout waiting for concurrency test release ClockPort.");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Concurrency test interrupted.", ex);
                }
            }
            return fixedInstant;
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        ControllableClock clockPort() {
            return new ControllableClock(BASE_TIME.plusSeconds(300));
        }
    }
}
