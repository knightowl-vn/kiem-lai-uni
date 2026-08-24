package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.VolumeHasPublishedChaptersException;
import com.universe.novel.application.exceptions.VolumeNotPublishedException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.application.volume.ArchiveVolumeCommand;
import com.universe.novel.application.volume.ArchiveVolumeUseCase;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.infrastructure.persistence.chapter.ChapterPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.revision.ChapterRevisionPersistenceAdapter;
import com.universe.novel.infrastructure.persistence.volume.VolumePersistenceAdapter;
import com.universe.shared.id.UuidGeneratorAdapter;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
@Import({ VolumePersistenceAdapter.class, ChapterPersistenceAdapter.class, ChapterRevisionPersistenceAdapter.class,
		ChapterRevisionRecorder.class, UuidGeneratorAdapter.class, ArchiveVolumeUseCase.class, PublishChapterUseCase.class,
		VolumeArchivePublishChapterConcurrencyIntegrationTest.TestConfig.class })
class VolumeArchivePublishChapterConcurrencyIntegrationTest {

	private static final UUID VOLUME_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

	private static final UUID CHAPTER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");

	private static final UUID ADMIN_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");

	private static final int VOLUME_SORT_ORDER = 2_000_000_001;

	private static final Instant BASE_TIME = Instant.parse("2026-08-18T00:00:00Z");

	private final VolumeRepositoryPort volumeRepositoryPort;

	private final ChapterRepositoryPort chapterRepositoryPort;

	private final ArchiveVolumeUseCase archiveVolumeUseCase;

	private final PublishChapterUseCase publishChapterUseCase;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final ControllableClock clock;

	@Autowired
	VolumeArchivePublishChapterConcurrencyIntegrationTest(VolumeRepositoryPort volumeRepositoryPort,
			ChapterRepositoryPort chapterRepositoryPort, ArchiveVolumeUseCase archiveVolumeUseCase,
			PublishChapterUseCase publishChapterUseCase, JdbcTemplate jdbcTemplate,
			PlatformTransactionManager transactionManager, ControllableClock clock) {
		this.volumeRepositoryPort = volumeRepositoryPort;

		this.chapterRepositoryPort = chapterRepositoryPort;

		this.archiveVolumeUseCase = archiveVolumeUseCase;

		this.publishChapterUseCase = publishChapterUseCase;

		this.jdbcTemplate = jdbcTemplate;

		this.transactionTemplate = new TransactionTemplate(transactionManager);

		this.clock = clock;
	}

	@BeforeEach
	void setUp() {
		cleanupTestRows();

		clock.reset();

		seedPublishedVolumeAndDraftChapter();
	}

	@AfterEach
	void tearDown() {
		/*
		 * Nếu test fail giữa chừng, tránh để thread bị giữ tại latch.
		 */
		clock.release();

		cleanupTestRows();
	}

	@Test
	@DisplayName("Archive thắng lock thì Publish Chapter phải bị từ chối")
	void shouldPreventPublishWhenArchiveWinsVolumeLock() throws Exception {

		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			/*
			 * Archive sẽ:
			 *
			 * 1. lock Volume 2. kiểm tra chưa có Chapter PUBLISHED 3. dừng tại
			 * ClockPort.now()
			 *
			 * Trong lúc đó lock Volume vẫn còn giữ.
			 */
			clock.blockThread("archive-thread");

			Future<?> archiveFuture = executor.submit(namedTask("archive-thread",
					() -> archiveVolumeUseCase.execute(new ArchiveVolumeCommand(VOLUME_ID, ADMIN_ID))));

			assertThat(clock.awaitBlocked(5, TimeUnit.SECONDS)).isTrue();

			/*
			 * Publish Chapter bắt đầu sau khi Archive đã giữ PESSIMISTIC_WRITE lock.
			 *
			 * Nó phải dừng tại findByIdForUpdate(Volume).
			 */
			Future<?> publishFuture = executor.submit(namedTask("publish-thread",
					() -> publishChapterUseCase.execute(new PublishChapterCommand(CHAPTER_ID, ADMIN_ID))));

			/*
			 * Cho publish một khoảng nhỏ để đi tới DB lock.
			 *
			 * Nếu locking không hoạt động, Future có thể hoàn tất ngay và test sẽ bắt được.
			 */
			Thread.sleep(300);

			assertThat(publishFuture.isDone()).isFalse();

			/*
			 * Cho Archive tiếp tục:
			 *
			 * Volume → ARCHIVED transaction commit lock được release.
			 */
			clock.release();

			archiveFuture.get(5, TimeUnit.SECONDS);

			/*
			 * Publish lấy được lock sau đó nhưng phải đọc thấy Volume đã ARCHIVED và bị
			 * reject.
			 */
			assertThatThrownBy(() -> publishFuture.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(VolumeNotPublishedException.class);

			assertFinalState(VolumeStatus.ARCHIVED, ChapterStatus.DRAFT);

		} finally {
			clock.release();

			executor.shutdownNow();

			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	@DisplayName("Publish thắng lock thì Archive Volume phải bị từ chối")
	void shouldPreventArchiveWhenPublishWinsVolumeLock() throws Exception {

		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			/*
			 * Publish:
			 *
			 * 1. load Chapter 2. lock parent Volume 3. xác nhận Volume PUBLISHED 4. dừng
			 * tại ClockPort.now()
			 *
			 * Volume lock vẫn còn giữ.
			 */
			clock.blockThread("publish-thread");

			Future<?> publishFuture = executor.submit(namedTask("publish-thread",
					() -> publishChapterUseCase.execute(new PublishChapterCommand(CHAPTER_ID, ADMIN_ID))));

			assertThat(clock.awaitBlocked(5, TimeUnit.SECONDS)).isTrue();

			/*
			 * Archive bắt đầu sau đó.
			 *
			 * Nó cũng gọi findByIdForUpdate(Volume), nên phải chờ Publish commit.
			 */
			Future<?> archiveFuture = executor.submit(namedTask("archive-thread",
					() -> archiveVolumeUseCase.execute(new ArchiveVolumeCommand(VOLUME_ID, ADMIN_ID))));

			Thread.sleep(300);

			assertThat(archiveFuture.isDone()).isFalse();

			/*
			 * Publish tiếp tục:
			 *
			 * Chapter → PUBLISHED commit release Volume lock.
			 */
			clock.release();

			publishFuture.get(5, TimeUnit.SECONDS);

			/*
			 * Archive giờ lấy lock được.
			 *
			 * Sau lock, nó kiểm:
			 *
			 * existsPublishedByVolumeId(...)
			 *
			 * và phải thấy Chapter vừa publish.
			 */
			assertThatThrownBy(() -> archiveFuture.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(VolumeHasPublishedChaptersException.class);

			assertFinalState(VolumeStatus.PUBLISHED, ChapterStatus.PUBLISHED);

		} finally {
			clock.release();

			executor.shutdownNow();

			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private void seedPublishedVolumeAndDraftChapter() {

		/*
		 * CREATE Volume DRAFT.
		 */
		transactionTemplate.executeWithoutResult(status -> {

			Volume volume = Volume.createDraft(VOLUME_ID, "Integration Lock Volume",
					new Slug("integration-lock-volume"), "Volume dùng cho concurrency integration test.",
					VOLUME_SORT_ORDER, ADMIN_ID, BASE_TIME);

			volumeRepositoryPort.save(volume, 0L);
		});

		/*
		 * Publish Volume.
		 */
		transactionTemplate.executeWithoutResult(status -> {

			Volume volume = volumeRepositoryPort.findById(VOLUME_ID).orElseThrow();

			long expectedVersion = volume.getAggregateVersion();

			volume.publish(ADMIN_ID, BASE_TIME.plusSeconds(10));

			volumeRepositoryPort.save(volume, expectedVersion);
		});

		/*
		 * CREATE Chapter DRAFT.
		 */
		transactionTemplate.executeWithoutResult(status -> {

			// Sửa số 1 thành một số rất lớn, ví dụ 999_999
			Chapter chapter = Chapter.createDraft(
			        CHAPTER_ID,
			        VOLUME_ID,
			        999_999, // <--- Thay đổi ở đây
			        "Integration Lock Chapter",
			        new Slug("integration-lock-chapter"),
			        "Chapter dùng cho concurrency test.",
			        "Nội dung Chapter.",
			        ADMIN_ID,
			        BASE_TIME.plusSeconds(20));

			chapterRepositoryPort.save(chapter, 0L);
		});
	}

	private void assertFinalState(VolumeStatus expectedVolumeStatus, ChapterStatus expectedChapterStatus) {
		State state = transactionTemplate.execute(status -> {

			Volume volume = volumeRepositoryPort.findById(VOLUME_ID).orElseThrow();

			Chapter chapter = chapterRepositoryPort.findById(CHAPTER_ID).orElseThrow();

			return new State(volume.getStatus(), chapter.getStatus());
		});

		assertThat(state).isNotNull();

		assertThat(state.volumeStatus()).isEqualTo(expectedVolumeStatus);

		assertThat(state.chapterStatus()).isEqualTo(expectedChapterStatus);

		/*
		 * Invariant quan trọng nhất.
		 */
		boolean invalidState = state.volumeStatus() == VolumeStatus.ARCHIVED
				&& state.chapterStatus() == ChapterStatus.PUBLISHED;

		assertThat(invalidState).isFalse();
	}

	private Callable<Object> namedTask(String threadName, Callable<?> task) {
		return () -> {

			Thread.currentThread().setName(threadName);

			Object result = task.call();

			return result;
		};
	}

	private void cleanupTestRows() {

		/*
		 * Revision trước vì FK chapter_id, sau đó Chapter, sau đó Volume.
		 */
		jdbcTemplate.update("""
				DELETE FROM novel_chapter_revisions
				WHERE chapter_id = ?
				""", CHAPTER_ID.toString());

		jdbcTemplate.update("""
				DELETE FROM novel_chapters
				WHERE id = ?
				""", CHAPTER_ID.toString());

		jdbcTemplate.update("""
				DELETE FROM novel_volumes
				WHERE id = ?
				""", VOLUME_ID.toString());
	}

	private record State(VolumeStatus volumeStatus, ChapterStatus chapterStatus) {
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
			releaseLatch.countDown();
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
						throw new IllegalStateException("Timeout khi chờ concurrency test release ClockPort.");
					}

				} catch (InterruptedException ex) {

					Thread.currentThread().interrupt();

					throw new IllegalStateException("Concurrency test bị interrupt.", ex);
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
			return new ControllableClock(BASE_TIME.plusSeconds(60));
		}
	}
}