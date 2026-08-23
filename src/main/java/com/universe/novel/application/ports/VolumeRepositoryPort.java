package com.universe.novel.application.ports;

import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VolumeRepositoryPort {

    Optional<Volume> findById(
            UUID id
    );

    Optional<Volume> findByIdForUpdate(
            UUID id
    );

    Optional<Volume> findBySlug(
            Slug slug
    );

    List<Volume> findAllOrderBySortOrder();

    boolean existsBySlug(
            Slug slug
    );

    boolean existsBySortOrder(
            int sortOrder
    );

    Volume save(
            Volume volume,
            long expectedAggregateVersion
    );
}
