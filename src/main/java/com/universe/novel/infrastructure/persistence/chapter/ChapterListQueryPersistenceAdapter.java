package com.universe.novel.infrastructure.persistence.chapter;

import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ChapterListQueryPersistenceAdapter
        implements ChapterListQueryPort {

    private final SpringDataChapterJpaRepository repository;

    public ChapterListQueryPersistenceAdapter(
            SpringDataChapterJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public ChapterListPageDTO
    findAllByVolumeIdOrderByChapterNumber(
            UUID volumeId,
            String keyword,
            String status,
            int page,
            int size
    ) {
        if (volumeId == null) {
            return new ChapterListPageDTO(
                    List.of(),
                    page,
                    size,
                    0L,
                    0,
                    false,
                    false
            );
        }

        Page<ChapterListItemProjection> result =
                repository.findListItems(
                        volumeId.toString(),
                        keyword,
                        status,
                        PageRequest.of(
                                page - 1,
                                size
                        )
                );

        List<ChapterListItemDTO> items =
                result.getContent()
                        .stream()
                        .map(
                                this::toDTO
                        )
                        .toList();

        return new ChapterListPageDTO(
                items,
                page,
                size,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasPrevious(),
                result.hasNext()
        );
    }

    private ChapterListItemDTO toDTO(
            ChapterListItemProjection projection
    ) {
        return new ChapterListItemDTO(
                UUID.fromString(
                        projection.getId()
                ),
                projection.getChapterNumber(),
                projection.getTitle(),
                projection.getSlug(),
                projection.getStatus(),
                projection.getUpdatedAt()
        );
    }
}
