package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.id.IdGeneratorPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reusable application-level recorder for creating and persisting Chapter revision snapshots.
 */
@Component
public class ChapterRevisionRecorder {

    private final ChapterRevisionRepositoryPort
            chapterRevisionRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    public ChapterRevisionRecorder(
            ChapterRevisionRepositoryPort chapterRevisionRepositoryPort,
            IdGeneratorPort idGeneratorPort
    ) {
        this.chapterRevisionRepositoryPort =
                Objects.requireNonNull(
                        chapterRevisionRepositoryPort,
                        "ChapterRevisionRepositoryPort không được để trống."
                );

        this.idGeneratorPort =
                Objects.requireNonNull(
                        idGeneratorPort,
                        "IdGeneratorPort không được để trống."
                );
    }

    /**
     * Ghi lại một snapshot revision cho Chapter đã được mutate và lưu thành công.
     */
    public ChapterRevision record(
            Chapter chapter,
            ChapterRevisionChangeType changeType,
            UUID actorId,
            String editSummary
    ) {
        Objects.requireNonNull(
                chapter,
                "Chapter không được để trống khi ghi nhận revision."
        );

        Objects.requireNonNull(
                changeType,
                "Loại thay đổi revision không được để trống."
        );

        Objects.requireNonNull(
                actorId,
                "Người thực hiện không được để trống."
        );

        UUID revisionId =
                idGeneratorPort.generate();

        Instant createdAt =
                chapter.getUpdatedAt() != null
                        ? chapter.getUpdatedAt()
                        : chapter.getCreatedAt();

        ChapterRevision revision =
                ChapterRevision.createSnapshot(
                        revisionId,
                        chapter,
                        changeType,
                        editSummary,
                        actorId,
                        createdAt
                );

        chapterRevisionRepositoryPort.save(
                revision
        );

        return revision;
    }
}
