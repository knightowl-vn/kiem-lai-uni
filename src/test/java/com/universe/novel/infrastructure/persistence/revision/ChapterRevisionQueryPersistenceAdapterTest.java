package com.universe.novel.infrastructure.persistence.revision;

import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListItemDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChapterRevisionQueryPersistenceAdapterTest {

    private SpringDataChapterRevisionJpaRepository repository;
    private ChapterRevisionQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(SpringDataChapterRevisionJpaRepository.class);
        adapter = new ChapterRevisionQueryPersistenceAdapter(repository);
    }

    @Test
    void shouldListRevisionsWithPaginationAndDescendingOrder() {
        UUID chapterId = UUID.randomUUID();
        UUID rev1Id = UUID.randomUUID();
        UUID rev2Id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterRevisionJpaEntity entity2 = createEntity(rev2Id, chapterId, 2L, "UPDATE_DRAFT", "DRAFT", actorId, now);
        ChapterRevisionJpaEntity entity1 = createEntity(rev1Id, chapterId, 1L, "CREATE_DRAFT", "DRAFT", actorId, now.minusSeconds(60));

        Page<ChapterRevisionJpaEntity> pageResult = new PageImpl<>(
                List.of(entity2, entity1),
                Pageable.ofSize(10),
                2L
        );

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findByChapterId(eq(chapterId.toString()), pageableCaptor.capture()))
                .thenReturn(pageResult);

        ChapterRevisionListPageDTO result = adapter.listRevisions(chapterId, 1, 10);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalItems()).isEqualTo(2L);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.items()).hasSize(2);

        ChapterRevisionListItemDTO firstItem = result.items().get(0);
        assertThat(firstItem.id()).isEqualTo(rev2Id);
        assertThat(firstItem.revisionNumber()).isEqualTo(2L);
        assertThat(firstItem.changeType()).isEqualTo(ChapterRevisionChangeType.UPDATE_DRAFT);

        Pageable capturedPageable = pageableCaptor.getValue();
        assertThat(capturedPageable.getPageNumber()).isEqualTo(0);
        assertThat(capturedPageable.getPageSize()).isEqualTo(10);
        assertThat(capturedPageable.getSort().getOrderFor("revisionNumber"))
                .isNotNull();
        assertThat(capturedPageable.getSort().getOrderFor("revisionNumber").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void shouldReturnEmptyPageWhenChapterIdIsNull() {
        ChapterRevisionListPageDTO result = adapter.listRevisions(null, 1, 10);
        assertThat(result.items()).isEmpty();
        assertThat(result.totalItems()).isEqualTo(0L);
    }

    @Test
    void shouldGetRevisionDetailWithRawMarkdownAndNullHtml() {
        UUID id = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID volumeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        ChapterRevisionJpaEntity entity = new ChapterRevisionJpaEntity();
        entity.setId(id.toString());
        entity.setChapterId(chapterId.toString());
        entity.setVolumeId(volumeId.toString());
        entity.setRevisionNumber(3L);
        entity.setContentVersion(2L);
        entity.setChapterNumber(5);
        entity.setTitle("Chương 5: Đại Đạo");
        entity.setSlug("quyen-1-chuong-5");
        entity.setSummary("Tóm tắt chương 5");
        entity.setContent("# Tiêu đề lớn\n\nNội dung thô markdown...");
        entity.setStatus("DRAFT");
        entity.setChangeType("UPDATE_DRAFT");
        entity.setEditSummary("Sửa lỗi chính tả");
        entity.setEditedBy(actorId.toString());
        entity.setCreatedAt(now);

        when(repository.findByChapterIdAndRevisionNumber(chapterId.toString(), 3L))
                .thenReturn(Optional.of(entity));

        Optional<ChapterRevisionDetailDTO> detail = adapter.getRevisionDetail(chapterId, 3L);

        assertThat(detail).isPresent();
        ChapterRevisionDetailDTO dto = detail.get();
        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.chapterId()).isEqualTo(chapterId);
        assertThat(dto.volumeId()).isEqualTo(volumeId);
        assertThat(dto.revisionNumber()).isEqualTo(3L);
        assertThat(dto.contentVersion()).isEqualTo(2L);
        assertThat(dto.chapterNumber()).isEqualTo(5);
        assertThat(dto.title()).isEqualTo("Chương 5: Đại Đạo");
        assertThat(dto.slug().value()).isEqualTo("quyen-1-chuong-5");
        assertThat(dto.summary()).isEqualTo("Tóm tắt chương 5");
        assertThat(dto.content()).isEqualTo("# Tiêu đề lớn\n\nNội dung thô markdown...");
        assertThat(dto.contentHtml()).isNull(); // Rendering belongs to application use cases
        assertThat(dto.status()).isEqualTo(ChapterStatus.DRAFT);
        assertThat(dto.changeType()).isEqualTo(ChapterRevisionChangeType.UPDATE_DRAFT);
        assertThat(dto.editSummary()).isEqualTo("Sửa lỗi chính tả");
        assertThat(dto.editedBy()).isEqualTo(actorId);
        assertThat(dto.createdAt()).isEqualTo(now);
    }

    @Test
    void shouldReturnEmptyDetailWhenNotFoundOrInvalid() {
        assertThat(adapter.getRevisionDetail(null, 1L)).isEmpty();
        assertThat(adapter.getRevisionDetail(UUID.randomUUID(), 0L)).isEmpty();

        UUID chapterId = UUID.randomUUID();
        when(repository.findByChapterIdAndRevisionNumber(chapterId.toString(), 99L))
                .thenReturn(Optional.empty());

        assertThat(adapter.getRevisionDetail(chapterId, 99L)).isEmpty();
    }

    private ChapterRevisionJpaEntity createEntity(
            UUID id,
            UUID chapterId,
            long revisionNumber,
            String changeType,
            String status,
            UUID actorId,
            Instant createdAt
    ) {
        ChapterRevisionJpaEntity entity = new ChapterRevisionJpaEntity();
        entity.setId(id.toString());
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
        entity.setEditedBy(actorId.toString());
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
