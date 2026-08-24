package com.universe.novel.infrastructure.persistence.revision;

import com.universe.novel.application.ports.ChapterRevisionQueryPort;
import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListItemDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChapterRevisionQueryPersistenceAdapter
        implements ChapterRevisionQueryPort {

    private final SpringDataChapterRevisionJpaRepository
            repository;

    public ChapterRevisionQueryPersistenceAdapter(
            SpringDataChapterRevisionJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public ChapterRevisionListPageDTO listRevisions(
            UUID chapterId,
            int page,
            int size
    ) {
        if (chapterId == null) {
            return new ChapterRevisionListPageDTO(
                    List.of(),
                    page,
                    size,
                    0L,
                    0,
                    false,
                    false
            );
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);

        Pageable pageable =
                PageRequest.of(
                        safePage - 1,
                        safeSize,
                        Sort.by(
                                Sort.Direction.DESC,
                                "revisionNumber"
                        )
                );

        Page<ChapterRevisionJpaEntity> pageResult =
                repository.findByChapterId(
                        chapterId.toString(),
                        pageable
                );

        List<ChapterRevisionListItemDTO> items =
                pageResult.getContent()
                        .stream()
                        .map(this::toListItemDTO)
                        .toList();

        return new ChapterRevisionListPageDTO(
                items,
                safePage,
                safeSize,
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.hasPrevious(),
                pageResult.hasNext()
        );
    }

    @Override
    public Optional<ChapterRevisionDetailDTO> getRevisionDetail(
            UUID chapterId,
            long revisionNumber
    ) {
        if (chapterId == null || revisionNumber < 1L) {
            return Optional.empty();
        }

        return repository
                .findByChapterIdAndRevisionNumber(
                        chapterId.toString(),
                        revisionNumber
                )
                .map(this::toDetailDTO);
    }

    private ChapterRevisionListItemDTO toListItemDTO(
            ChapterRevisionJpaEntity entity
    ) {
        return new ChapterRevisionListItemDTO(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getChapterId()),
                entity.getRevisionNumber(),
                entity.getContentVersion(),
                entity.getChapterNumber(),
                entity.getTitle(),
                ChapterStatus.valueOf(entity.getStatus()),
                ChapterRevisionChangeType.valueOf(entity.getChangeType()),
                entity.getEditSummary(),
                UUID.fromString(entity.getEditedBy()),
                entity.getCreatedAt()
        );
    }

    private ChapterRevisionDetailDTO toDetailDTO(
            ChapterRevisionJpaEntity entity
    ) {
        return new ChapterRevisionDetailDTO(
                UUID.fromString(entity.getId()),
                UUID.fromString(entity.getChapterId()),
                UUID.fromString(entity.getVolumeId()),
                entity.getRevisionNumber(),
                entity.getContentVersion(),
                entity.getChapterNumber(),
                entity.getTitle(),
                new Slug(entity.getSlug()),
                entity.getSummary(),
                entity.getContent(),
                null, // Markdown rendering belongs to application layer
                ChapterStatus.valueOf(entity.getStatus()),
                ChapterRevisionChangeType.valueOf(entity.getChangeType()),
                entity.getEditSummary(),
                UUID.fromString(entity.getEditedBy()),
                entity.getCreatedAt()
        );
    }
}
