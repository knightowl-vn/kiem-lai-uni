package com.universe.novel.infrastructure.persistence.revision;

import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ChapterRevisionPersistenceAdapter
        implements ChapterRevisionRepositoryPort {

    private final SpringDataChapterRevisionJpaRepository
            repository;

    public ChapterRevisionPersistenceAdapter(
            SpringDataChapterRevisionJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public void save(
            ChapterRevision revision
    ) {
        Objects.requireNonNull(
                revision,
                "Chapter revision không được để trống."
        );

        ChapterRevisionJpaEntity entity =
                mapToEntity(revision);

        repository.save(entity);
    }

    @Override
    public Optional<ChapterRevision> findByChapterIdAndRevisionNumber(
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
                .map(this::toDomain);
    }

    @Override
    public boolean canSafelyHardDelete(
            UUID chapterId
    ) {
        if (chapterId == null) {
            return false;
        }

        List<ChapterRevisionJpaEntity> revisions =
                repository.findByChapterIdOrderByRevisionNumberAsc(
                        chapterId.toString()
                );

        if (revisions.isEmpty()) {
            return false;
        }

        boolean hasCreateDraft = false;

        for (ChapterRevisionJpaEntity rev : revisions) {
            if (!"DRAFT".equals(rev.getStatus())) {
                return false;
            }

            String changeType = rev.getChangeType();
            switch (changeType) {
                case "CREATE_DRAFT" -> hasCreateDraft = true;
                case "UPDATE_DRAFT", "MOVE_VOLUME", "RESTORE_REVISION" -> {
                    // Allowed pure draft operations
                }
                default -> {
                    // Rejects BASELINE, PUBLISH, UNPUBLISH, ARCHIVE, RESTORE_TO_DRAFT, or unknown
                    return false;
                }
            }
        }

        return hasCreateDraft;
    }

    @Override
    public void deleteAllByChapterId(
            UUID chapterId
    ) {
        if (chapterId == null) {
            return;
        }

        repository.deleteByChapterId(
                chapterId.toString()
        );
    }

    private ChapterRevisionJpaEntity mapToEntity(
            ChapterRevision revision
    ) {
        ChapterRevisionJpaEntity entity =
                new ChapterRevisionJpaEntity();

        entity.setId(revision.id().toString());
        entity.setChapterId(revision.chapterId().toString());
        entity.setVolumeId(revision.volumeId().toString());
        entity.setRevisionNumber(revision.revisionNumber());
        entity.setContentVersion(revision.contentVersion());
        entity.setChapterNumber(revision.chapterNumber());
        entity.setTitle(revision.title());
        entity.setSlug(revision.slug().value());
        entity.setSummary(revision.summary());
        entity.setContent(revision.content());
        entity.setStatus(revision.status().name());
        entity.setChangeType(revision.changeType().name());
        entity.setEditSummary(revision.editSummary());
        entity.setEditedBy(revision.editedBy().toString());
        entity.setCreatedAt(revision.createdAt());

        return entity;
    }

    private ChapterRevision toDomain(
            ChapterRevisionJpaEntity entity
    ) {
        return new ChapterRevision(
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
                ChapterStatus.valueOf(entity.getStatus()),
                ChapterRevisionChangeType.valueOf(entity.getChangeType()),
                entity.getEditSummary(),
                UUID.fromString(entity.getEditedBy()),
                entity.getCreatedAt()
        );
    }
}
