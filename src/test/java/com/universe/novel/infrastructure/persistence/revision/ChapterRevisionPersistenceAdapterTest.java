package com.universe.novel.infrastructure.persistence.revision;

import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChapterRevisionPersistenceAdapterTest {

    private SpringDataChapterRevisionJpaRepository repository;
    private ChapterRevisionPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(SpringDataChapterRevisionJpaRepository.class);
        adapter = new ChapterRevisionPersistenceAdapter(repository);
    }

    @Test
    void shouldSaveRevisionCorrectly() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterRevision revision = new ChapterRevision(
                id,
                chapterId,
                volumeId,
                1L,
                1L,
                1,
                "Tiêu đề chương",
                new Slug("quyen-1-chuong-1"),
                "Tóm tắt",
                "Nội dung thô Markdown",
                ChapterStatus.DRAFT,
                ChapterRevisionChangeType.CREATE_DRAFT,
                "Ghi chú",
                actorId,
                now
        );

        adapter.save(revision);

        ArgumentCaptor<ChapterRevisionJpaEntity> captor =
                ArgumentCaptor.forClass(ChapterRevisionJpaEntity.class);
        verify(repository).save(captor.capture());

        ChapterRevisionJpaEntity entity = captor.getValue();
        assertThat(entity.getId()).isEqualTo(id.toString());
        assertThat(entity.getChapterId()).isEqualTo(chapterId.toString());
        assertThat(entity.getVolumeId()).isEqualTo(volumeId.toString());
        assertThat(entity.getRevisionNumber()).isEqualTo(1L);
        assertThat(entity.getContentVersion()).isEqualTo(1L);
        assertThat(entity.getChapterNumber()).isEqualTo(1);
        assertThat(entity.getTitle()).isEqualTo("Tiêu đề chương");
        assertThat(entity.getSlug()).isEqualTo("quyen-1-chuong-1");
        assertThat(entity.getSummary()).isEqualTo("Tóm tắt");
        assertThat(entity.getContent()).isEqualTo("Nội dung thô Markdown");
        assertThat(entity.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getChangeType()).isEqualTo("CREATE_DRAFT");
        assertThat(entity.getEditSummary()).isEqualTo("Ghi chú");
        assertThat(entity.getEditedBy()).isEqualTo(actorId.toString());
        assertThat(entity.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void shouldFindRevisionByChapterIdAndRevisionNumber() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterRevisionJpaEntity entity = new ChapterRevisionJpaEntity();
        entity.setId(id.toString());
        entity.setChapterId(chapterId.toString());
        entity.setVolumeId(volumeId.toString());
        entity.setRevisionNumber(2L);
        entity.setContentVersion(1L);
        entity.setChapterNumber(1);
        entity.setTitle("Tiêu đề chương v2");
        entity.setSlug("quyen-1-chuong-1");
        entity.setSummary("Tóm tắt");
        entity.setContent("Nội dung");
        entity.setStatus("DRAFT");
        entity.setChangeType("UPDATE_DRAFT");
        entity.setEditSummary("Cập nhật");
        entity.setEditedBy(actorId.toString());
        entity.setCreatedAt(now);

        when(repository.findByChapterIdAndRevisionNumber(chapterId.toString(), 2L))
                .thenReturn(Optional.of(entity));

        Optional<ChapterRevision> result =
                adapter.findByChapterIdAndRevisionNumber(chapterId, 2L);

        assertThat(result).isPresent();
        ChapterRevision rev = result.get();
        assertThat(rev.id()).isEqualTo(id);
        assertThat(rev.chapterId()).isEqualTo(chapterId);
        assertThat(rev.revisionNumber()).isEqualTo(2L);
        assertThat(rev.title()).isEqualTo("Tiêu đề chương v2");
        assertThat(rev.changeType()).isEqualTo(ChapterRevisionChangeType.UPDATE_DRAFT);
    }

    @Test
    void shouldReturnEmptyWhenRevisionNotFoundOrInvalidArgs() {
        assertThat(adapter.findByChapterIdAndRevisionNumber(null, 1L)).isEmpty();
        assertThat(adapter.findByChapterIdAndRevisionNumber(UUID.randomUUID(), 0L)).isEmpty();

        UUID chapterId = UUID.randomUUID();
        when(repository.findByChapterIdAndRevisionNumber(chapterId.toString(), 1L))
                .thenReturn(Optional.empty());

        assertThat(adapter.findByChapterIdAndRevisionNumber(chapterId, 1L)).isEmpty();
    }

    @Test
    void shouldDeleteAllByChapterId() {
        UUID chapterId = UUID.randomUUID();
        adapter.deleteAllByChapterId(chapterId);
        verify(repository).deleteByChapterId(chapterId.toString());
    }

    /*
     * =========================================================================
     * canSafelyHardDelete STRICT POSITIVE PROOF TESTS (Cases A - J)
     * =========================================================================
     */

    @Test
    void shouldReturnTrueForCaseA_CreateDraftOnly() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isTrue();
    }

    @Test
    void shouldReturnTrueForCaseB_CreateDraftPlusUpdateDraftPlusMoveVolume() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 2L, "UPDATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 3L, "MOVE_VOLUME", "DRAFT"),
                createEntity(chapterId, 4L, "RESTORE_REVISION", "DRAFT")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isTrue();
    }

    @Test
    void shouldReturnFalseForCaseC_Baseline() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "BASELINE", "DRAFT")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseD_CreateDraftPlusPublish() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 2L, "PUBLISH", "PUBLISHED")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseE_CreateDraftPlusPublishPlusUnpublish() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 2L, "PUBLISH", "PUBLISHED"),
                createEntity(chapterId, 3L, "UNPUBLISH", "DRAFT")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseF_CreateDraftPlusArchive() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 2L, "ARCHIVE", "ARCHIVED")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseG_CreateDraftPlusArchivePlusRestoreToDraft() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 2L, "ARCHIVE", "ARCHIVED"),
                createEntity(chapterId, 3L, "RESTORE_TO_DRAFT", "DRAFT")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseH_ZeroRevisions() {
        UUID chapterId = UUID.randomUUID();
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(List.of());

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
        assertThat(adapter.canSafelyHardDelete(null)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseI_AnyRevisionWithStatusNotDraft() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "PUBLISHED")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    @Test
    void shouldReturnFalseForCaseJ_UnexpectedOrNonPureDraftChangeType() {
        UUID chapterId = UUID.randomUUID();
        List<ChapterRevisionJpaEntity> history = List.of(
                createEntity(chapterId, 1L, "CREATE_DRAFT", "DRAFT"),
                createEntity(chapterId, 2L, "UNKNOWN_CHANGE_TYPE", "DRAFT")
        );
        when(repository.findByChapterIdOrderByRevisionNumberAsc(chapterId.toString()))
                .thenReturn(history);

        assertThat(adapter.canSafelyHardDelete(chapterId)).isFalse();
    }

    private ChapterRevisionJpaEntity createEntity(
            UUID chapterId,
            long revisionNumber,
            String changeType,
            String status
    ) {
        ChapterRevisionJpaEntity entity = new ChapterRevisionJpaEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setChapterId(chapterId.toString());
        entity.setVolumeId(UUID.randomUUID().toString());
        entity.setRevisionNumber(revisionNumber);
        entity.setContentVersion(1L);
        entity.setChapterNumber(1);
        entity.setTitle("Chương 1");
        entity.setSlug("quyen-1-chuong-1");
        entity.setSummary("Tóm tắt");
        entity.setContent("Nội dung");
        entity.setStatus(status);
        entity.setChangeType(changeType);
        entity.setEditedBy(UUID.randomUUID().toString());
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
