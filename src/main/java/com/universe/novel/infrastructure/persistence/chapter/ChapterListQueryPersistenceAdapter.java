package com.universe.novel.infrastructure.persistence.chapter;

import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    @Override
    public Map<UUID, ChapterListItemDTO> findListItemsByIds(Set<UUID> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) {
            return Map.of();
        }

        Set<String> idStrings = chapterIds.stream()
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toSet());

        if (idStrings.isEmpty()) {
            return Map.of();
        }

        return repository.findListItemsByIds(idStrings).stream()
                .map(this::toDTO)
                .collect(Collectors.toMap(
                        ChapterListItemDTO::id,
                        dto -> dto,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
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
