package com.universe.novel.infrastructure.persistence.profile;

import com.universe.novel.application.ports.NovelProfileRepositoryPort;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class NovelProfilePersistenceAdapter
        implements NovelProfileRepositoryPort {

    private final SpringDataNovelProfileJpaRepository
            novelProfileRepository;

    public NovelProfilePersistenceAdapter(
            SpringDataNovelProfileJpaRepository novelProfileRepository
    ) {
        this.novelProfileRepository =
                novelProfileRepository;
    }

    @Override
    public Optional<NovelProfileDTO> findBySlug(
            String slug
    ) {
        return novelProfileRepository
                .findBySlug(
                        slug
                )
                .map(
                        this::toDTO
                );
    }

    @Override
    public NovelProfileDTO update(
            String slug,
            String title,
            String author,
            String description,
            String coverImageUrl,
            String status,
            Instant updatedAt
    ) {
        NovelProfileJpaEntity entity =
                novelProfileRepository
                        .findBySlug(
                                slug
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Không tìm thấy hồ sơ tiểu thuyết với slug: "
                                        + slug
                        ));

        entity.update(
                title,
                author,
                description,
                coverImageUrl,
                status,
                updatedAt
        );

        NovelProfileJpaEntity savedEntity =
                novelProfileRepository.save(
                        entity
                );

        return toDTO(
                savedEntity
        );
    }

    private NovelProfileDTO toDTO(
            NovelProfileJpaEntity entity
    ) {
        return new NovelProfileDTO(
                UUID.fromString(
                        entity.getId()
                ),
                entity.getTitle(),
                entity.getSlug(),
                entity.getAuthor(),
                entity.getDescription(),
                entity.getCoverImageUrl(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
