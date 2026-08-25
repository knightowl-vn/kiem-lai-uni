package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.exceptions.DuplicateChapterBookmarkException;
import com.universe.novel.domain.reader.UserChapterBookmark;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChapterBookmarkPersistenceAdapterTest {

    private static final UUID BOOKMARK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID USER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-25T10:00:00Z");

    @Mock
    private SpringDataChapterBookmarkJpaRepository repository;

    private ChapterBookmarkPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ChapterBookmarkPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("existsByUserIdAndChapterId: trả true khi tồn tại bookmark")
    void shouldReturnTrueWhenBookmarkExists() {
        when(repository.existsByUserIdAndChapterId(USER_ID.toString(), CHAPTER_ID.toString()))
                .thenReturn(true);

        boolean exists = adapter.existsByUserIdAndChapterId(USER_ID, CHAPTER_ID);

        assertThat(exists).isTrue();
        verify(repository).existsByUserIdAndChapterId(USER_ID.toString(), CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("existsByUserIdAndChapterId: trả false khi userId hoặc chapterId là null")
    void shouldReturnFalseWhenUserIdOrChapterIdIsNull() {
        assertThat(adapter.existsByUserIdAndChapterId(null, CHAPTER_ID)).isFalse();
        assertThat(adapter.existsByUserIdAndChapterId(USER_ID, null)).isFalse();
        verify(repository, never()).existsByUserIdAndChapterId(any(), any());
    }

    @Test
    @DisplayName("save: chuyển đổi domain model sang entity và lưu trữ thành công")
    void shouldSaveBookmarkSuccessfully() {
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        adapter.save(bookmark);

        ArgumentCaptor<ChapterBookmarkJpaEntity> captor =
                ArgumentCaptor.forClass(ChapterBookmarkJpaEntity.class);
        verify(repository).saveAndFlush(captor.capture());

        ChapterBookmarkJpaEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(BOOKMARK_ID.toString());
        assertThat(entity.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(entity.getChapterId()).isEqualTo(CHAPTER_ID.toString());
        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("save: phát hiện uq_novel_chapter_bookmarks_user_chapter và dịch sang DuplicateChapterBookmarkException")
    void shouldTranslateUniqueConstraintViolationToDuplicateChapterBookmarkException() {
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry for key 'uq_novel_chapter_bookmarks_user_chapter'",
                new SQLException("Duplicate entry"),
                "uq_novel_chapter_bookmarks_user_chapter"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);

        when(repository.saveAndFlush(any())).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(bookmark))
                .isInstanceOf(DuplicateChapterBookmarkException.class)
                .hasMessageContaining(USER_ID.toString())
                .hasMessageContaining(CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("save: không nuốt các lỗi toàn vẹn dữ liệu khác (ví dụ FK failure)")
    void shouldRethrowOtherDataIntegrityViolations() {
        UserChapterBookmark bookmark = UserChapterBookmark.create(
                BOOKMARK_ID,
                USER_ID,
                CHAPTER_ID,
                CREATED_AT
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Cannot add or update a child row: a foreign key constraint fails",
                new SQLException("FK violation"),
                "fk_novel_chapter_bookmarks_chapter"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("FK violation", cve);

        when(repository.saveAndFlush(any())).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(bookmark))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateChapterBookmarkException.class);
    }

    @Test
    @DisplayName("deleteByUserIdAndChapterId: xóa bookmark và trả về số dòng bị xóa")
    void shouldDeleteBookmarkByUserIdAndChapterId() {
        when(repository.deleteByUserIdAndChapterId(USER_ID.toString(), CHAPTER_ID.toString()))
                .thenReturn(1);

        int count = adapter.deleteByUserIdAndChapterId(USER_ID, CHAPTER_ID);

        assertThat(count).isEqualTo(1);
        verify(repository).deleteByUserIdAndChapterId(USER_ID.toString(), CHAPTER_ID.toString());
    }

    @Test
    @DisplayName("deleteByUserIdAndChapterId: trả 0 khi userId hoặc chapterId là null")
    void shouldReturnZeroWhenDeleteWithNull() {
        assertThat(adapter.deleteByUserIdAndChapterId(null, CHAPTER_ID)).isEqualTo(0);
        assertThat(adapter.deleteByUserIdAndChapterId(USER_ID, null)).isEqualTo(0);
        verify(repository, never()).deleteByUserIdAndChapterId(any(), any());
    }
}
