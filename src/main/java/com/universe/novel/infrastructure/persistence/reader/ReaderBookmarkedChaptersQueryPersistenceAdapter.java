package com.universe.novel.infrastructure.persistence.reader;

import com.universe.novel.application.ports.ReaderBookmarkedChaptersQueryPort;
import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ReaderBookmarkedChaptersQueryPersistenceAdapter
        implements ReaderBookmarkedChaptersQueryPort {

    private final SpringDataChapterBookmarkJpaRepository repository;

    public ReaderBookmarkedChaptersQueryPersistenceAdapter(
            SpringDataChapterBookmarkJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataChapterBookmarkJpaRepository không được để trống."
        );
    }

    @Override
    public List<ReaderBookmarkedChapterDTO> findBookmarkedChaptersByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }

        return repository.findPublishedBookmarkedChaptersByUserId(userId.toString())
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public boolean isBookmarked(UUID userId, UUID chapterId) {
        if (userId == null || chapterId == null) {
            return false;
        }

        return repository.existsByUserIdAndChapterId(userId.toString(), chapterId.toString());
    }

    private ReaderBookmarkedChapterDTO toDTO(ReaderBookmarkedChapterProjection projection) {
        return new ReaderBookmarkedChapterDTO(
                UUID.fromString(projection.getChapterId()),
                projection.getChapterNumber(),
                projection.getChapterTitle(),
                projection.getChapterSlug(),
                projection.getVolumeTitle(),
                projection.getBookmarkedAt()
        );
    }
}
