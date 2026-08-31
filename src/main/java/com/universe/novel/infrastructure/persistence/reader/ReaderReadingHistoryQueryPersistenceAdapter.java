package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderReadingHistoryQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ReaderReadingHistoryQueryPersistenceAdapter
        implements ReaderReadingHistoryQueryPort {

    private final SpringDataReadingHistoryJpaRepository repository;

    public ReaderReadingHistoryQueryPersistenceAdapter(
            SpringDataReadingHistoryJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataReadingHistoryJpaRepository không được để trống."
        );
    }

    @Override
    public List<ReaderReadingHistoryDTO> findReadingHistoryByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }

        return repository.findPublishedReadingHistoryByUserId(userId.toString())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    private ReaderReadingHistoryDTO toDTO(ReaderReadingHistoryProjection projection) {
        return new ReaderReadingHistoryDTO(
                UUID.fromString(projection.getChapterId()),
                projection.getChapterNumber(),
                projection.getChapterTitle(),
                projection.getChapterSlug(),
                projection.getVolumeTitle(),
                projection.getLastReadAt()
        );
    }
}
