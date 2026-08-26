package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.DuplicateReadingHistoryException;
import com.universe.novel.domain.reader.UserChapterReadingHistory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
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
class ReadingHistoryPersistenceAdapterTest {

    private static final UUID HISTORY_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant T0 =
            Instant.parse("2026-08-26T08:00:00Z");

    private static final Instant T1 =
            Instant.parse("2026-08-26T08:30:00Z");

    @Mock
    private SpringDataReadingHistoryJpaRepository repository;

    private ReadingHistoryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReadingHistoryPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("findByUserIdAndChapterId: trả về domain model khi tìm thấy bản ghi")
    void shouldReturnDomainModelWhenRecordFound() {
        ReadingHistoryJpaEntity entity = new ReadingHistoryJpaEntity(
                HISTORY_ID.toString(),
                USER_ID.toString(),
                CHAPTER_ID.toString(),
                T0,
                T1
        );
        when(repository.findByUserIdAndChapterId(USER_ID.toString(), CHAPTER_ID.toString()))
                .thenReturn(Optional.of(entity));

        Optional<UserChapterReadingHistory> result =
                adapter.findByUserIdAndChapterId(USER_ID, CHAPTER_ID);

        assertThat(result).isPresent();
        UserChapterReadingHistory history = result.get();
        assertThat(history.getId()).isEqualTo(HISTORY_ID);
        assertThat(history.getUserId()).isEqualTo(USER_ID);
        assertThat(history.getChapterId()).isEqualTo(CHAPTER_ID);
        assertThat(history.getFirstReadAt()).isEqualTo(T0);
        assertThat(history.getLastReadAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("findByUserIdAndChapterId: trả về empty khi userId hoặc chapterId là null")
    void shouldReturnEmptyWhenArgumentsNull() {
        assertThat(adapter.findByUserIdAndChapterId(null, CHAPTER_ID)).isEmpty();
        assertThat(adapter.findByUserIdAndChapterId(USER_ID, null)).isEmpty();
        verify(repository, never()).findByUserIdAndChapterId(any(), any());
    }

    @Test
    @DisplayName("save: chuyển đổi domain model mới sang JPA entity và insert thành công")
    void shouldInsertNewHistoryRecordSuccessfully() {
        UserChapterReadingHistory domain = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        when(repository.findById(HISTORY_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ReadingHistoryJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UserChapterReadingHistory saved = adapter.save(domain);

        ArgumentCaptor<ReadingHistoryJpaEntity> captor =
                ArgumentCaptor.forClass(ReadingHistoryJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ReadingHistoryJpaEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(HISTORY_ID.toString());
        assertThat(entity.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(entity.getChapterId()).isEqualTo(CHAPTER_ID.toString());
        assertThat(entity.getFirstReadAt()).isEqualTo(T0);
        assertThat(entity.getLastReadAt()).isEqualTo(T0);

        assertThat(saved.getId()).isEqualTo(HISTORY_ID);
        assertThat(saved.getFirstReadAt()).isEqualTo(T0);
        assertThat(saved.getLastReadAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("save: cập nhật bản ghi đã tồn tại, bảo toàn firstReadAt và cập nhật lastReadAt")
    void shouldUpdateExistingHistoryRecordPreservingFirstReadAt() {
        UserChapterReadingHistory domain = UserChapterReadingHistory.rehydrate(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0,
                T1
        );

        ReadingHistoryJpaEntity existingEntity = new ReadingHistoryJpaEntity(
                HISTORY_ID.toString(),
                USER_ID.toString(),
                CHAPTER_ID.toString(),
                T0,
                T0
        );
        when(repository.findById(HISTORY_ID.toString())).thenReturn(Optional.of(existingEntity));
        when(repository.saveAndFlush(any(ReadingHistoryJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UserChapterReadingHistory saved = adapter.save(domain);

        assertThat(existingEntity.getFirstReadAt()).isEqualTo(T0);
        assertThat(existingEntity.getLastReadAt()).isEqualTo(T1);
        assertThat(saved.getFirstReadAt()).isEqualTo(T0);
        assertThat(saved.getLastReadAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("save: phát hiện vi phạm uq_novel_reading_history_user_chapter và dịch sang DuplicateReadingHistoryException")
    void shouldTranslateUniqueConstraintViolationToDuplicateReadingHistoryException() {
        UserChapterReadingHistory domain = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        when(repository.findById(HISTORY_ID.toString())).thenReturn(Optional.empty());

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry for key 'uq_novel_reading_history_user_chapter'",
                new SQLException("Duplicate entry"),
                "uq_novel_reading_history_user_chapter"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any())).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domain))
                .isInstanceOf(DuplicateReadingHistoryException.class)
                .hasMessageContaining(USER_ID.toString())
                .hasMessageContaining(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("save: không nuốt các lỗi toàn vẹn dữ liệu khác (ví dụ FK failure)")
    void shouldRethrowOtherDataIntegrityViolations() {
        UserChapterReadingHistory domain = UserChapterReadingHistory.createInitial(
                HISTORY_ID,
                USER_ID,
                CHAPTER_ID,
                T0
        );

        when(repository.findById(HISTORY_ID.toString())).thenReturn(Optional.empty());

        ConstraintViolationException cve = new ConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails",
                new SQLException("FK violation"),
                "fk_novel_reading_history_chapter"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("FK violation", cve);

        when(repository.saveAndFlush(any())).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(domain))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateReadingHistoryException.class);
    }

    @Test
    @DisplayName("save: ném IllegalArgumentException khi domain object là null")
    void shouldThrowExceptionWhenSavingNull() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserChapterReadingHistory không được để trống.");
    }

    @Test
    @DisplayName("pruneOldestEntriesExceedingLimit: gọi repository để xoá các bản ghi vượt quá giới hạn")
    void shouldInvokeRepositoryPruning() {
        adapter.pruneOldestEntriesExceedingLimit(USER_ID, 50);

        verify(repository).pruneOldestEntriesExceedingRetentionLimit(USER_ID.toString(), 50);
    }

    @Test
    @DisplayName("pruneOldestEntriesExceedingLimit: bỏ qua nếu userId là null hoặc limit <= 0")
    void shouldSkipPruningWhenArgumentsInvalid() {
        adapter.pruneOldestEntriesExceedingLimit(null, 50);
        adapter.pruneOldestEntriesExceedingLimit(USER_ID, 0);
        adapter.pruneOldestEntriesExceedingLimit(USER_ID, -1);

        verify(repository, never()).pruneOldestEntriesExceedingRetentionLimit(any(), any(int.class));
    }
}
