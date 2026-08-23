package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.profile.NovelProfileDTO;

import java.time.Instant;
import java.util.Optional;

public interface NovelProfileRepositoryPort {

    Optional<NovelProfileDTO> findBySlug(
            String slug
    );

    NovelProfileDTO update(
            String slug,
            String title,
            String author,
            String description,
            String coverImageUrl,
            String status,
            Instant updatedAt
    );
}
