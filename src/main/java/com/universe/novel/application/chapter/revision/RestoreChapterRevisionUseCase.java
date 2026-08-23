package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.chapter.ChapterDTOMapper;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionAlreadyCurrentException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.revision.ChapterRevision;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RestoreChapterRevisionUseCase {

    private static final int MAX_EDIT_SUMMARY_LENGTH =
            500;

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ChapterRevisionRepositoryPort
            chapterRevisionRepositoryPort;

    private final ChapterRevisionRecorder
            chapterRevisionRecorder;

    private final ClockPort
            clockPort;

    public RestoreChapterRevisionUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterRevisionRepositoryPort chapterRevisionRepositoryPort,
            ChapterRevisionRecorder chapterRevisionRecorder,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort =
                Objects.requireNonNull(
                        chapterRepositoryPort,
                        "ChapterRepositoryPort không được để trống."
                );

        this.chapterRevisionRepositoryPort =
                Objects.requireNonNull(
                        chapterRevisionRepositoryPort,
                        "ChapterRevisionRepositoryPort không được để trống."
                );

        this.chapterRevisionRecorder =
                Objects.requireNonNull(
                        chapterRevisionRecorder,
                        "ChapterRevisionRecorder không được để trống."
                );

        this.clockPort =
                Objects.requireNonNull(
                        clockPort,
                        "ClockPort không được để trống."
                );
    }

    @Transactional
    public ChapterDTO execute(
            RestoreChapterRevisionCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Restore chapter revision command không được để trống."
        );

        UUID chapterId =
                Objects.requireNonNull(
                        command.chapterId(),
                        "Chapter ID không được để trống."
                );

        long sourceRevisionNumber =
                command.sourceRevisionNumber();

        if (sourceRevisionNumber < 1L) {
            throw new IllegalArgumentException(
                    "Source revision number phải lớn hơn hoặc bằng 1."
            );
        }

        UUID actorId =
                Objects.requireNonNull(
                        command.actorId(),
                        "Actor ID không được để trống."
                );

        String resolvedEditSummary =
                resolveAndValidateEditSummary(
                        command.editSummary(),
                        sourceRevisionNumber
                );

        Chapter chapter =
                chapterRepositoryPort
                        .findById(
                                chapterId
                        )
                        .orElseThrow(() ->
                                new ChapterNotFoundException(
                                        chapterId
                                )
                        );

        if (chapter.getStatus() != ChapterStatus.DRAFT) {
            throw new IllegalStateException(
                    "Chỉ chương ở trạng thái DRAFT mới được khôi phục phiên bản lịch sử."
            );
        }

        ChapterRevision sourceRevision =
                chapterRevisionRepositoryPort
                        .findByChapterIdAndRevisionNumber(
                                chapterId,
                                sourceRevisionNumber
                        )
                        .orElseThrow(() ->
                                new ChapterRevisionNotFoundException(
                                        chapterId,
                                        sourceRevisionNumber
                                )
                        );

        if (Objects.equals(
                chapter.getTitle(),
                sourceRevision.title()
        ) && Objects.equals(
                chapter.getSummary(),
                sourceRevision.summary()
        ) && Objects.equals(
                chapter.getContent(),
                sourceRevision.content()
        )) {
            throw new ChapterRevisionAlreadyCurrentException(
                    chapterId,
                    sourceRevisionNumber
            );
        }

        Instant now =
                clockPort.now();

        chapter.restoreRevisionContent(
                sourceRevision.title(),
                sourceRevision.summary(),
                sourceRevision.content(),
                actorId,
                now
        );

        Chapter savedChapter =
                chapterRepositoryPort.save(
                        chapter,
                        command.expectedAggregateVersion()
                );

        chapterRevisionRecorder.record(
                savedChapter,
                ChapterRevisionChangeType.RESTORE_REVISION,
                actorId,
                resolvedEditSummary
        );

        return ChapterDTOMapper.toDTO(
                savedChapter
        );
    }

    private String resolveAndValidateEditSummary(
            String customEditSummary,
            long sourceRevisionNumber
    ) {
        if (customEditSummary == null || customEditSummary.isBlank()) {
            return "Khôi phục từ phiên bản #"
                    + sourceRevisionNumber;
        }

        String normalizedValue =
                customEditSummary.trim();

        if (normalizedValue.length() > MAX_EDIT_SUMMARY_LENGTH) {
            throw new IllegalArgumentException(
                    "Mô tả chỉnh sửa không được vượt quá "
                            + MAX_EDIT_SUMMARY_LENGTH
                            + " ký tự."
            );
        }

        return normalizedValue;
    }
}
