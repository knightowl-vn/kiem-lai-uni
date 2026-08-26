package com.universe.novel.application.ports;

import com.universe.novel.domain.reader.UserReadingProgress;

import java.util.Optional;
import java.util.UUID;

/**
 * Port giao tiếp persistence cho Aggregate UserReadingProgress.
 *
 * Thuần túy không phụ thuộc JPA Entity, Spring Data, hay các kiểu dữ liệu phân trang.
 */
public interface ReadingProgressRepositoryPort {

    Optional<UserReadingProgress> findByUserId(UUID userId);

    Optional<UserReadingProgress> findById(UUID id);

    UserReadingProgress save(UserReadingProgress progress);
}
