package com.universe.novel.application.chapter;

import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterNumberAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UpdateDraftChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    private final ChapterRevisionRecorder
            chapterRevisionRecorder;

    public UpdateDraftChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort,
            ChapterRevisionRecorder chapterRevisionRecorder
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;

        this.chapterRevisionRecorder =
                chapterRevisionRecorder;
    }

    @Transactional
    public ChapterDTO execute(
            UpdateDraftChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Update draft chapter command không được để trống."
        );

        UUID chapterId =
                Objects.requireNonNull(
                        command.chapterId(),
                        "Chapter ID không được để trống."
                );

        int chapterNumber =
                Objects.requireNonNull(
                        command.chapterNumber(),
                        "Số chương không được để trống."
                );

        validateChapterNumber(
                chapterNumber
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

        Volume volume =
                volumeRepositoryPort
                        .findById(
                                chapter.getVolumeId()
                        )
                        .orElseThrow(() ->
                                new VolumeNotFoundException(
                                        chapter.getVolumeId()
                                )
                        );

        Slug generatedSlug =
                ChapterSlugGenerator.generate(
                        volume,
                        chapterNumber
                );

        if (chapter.getChapterNumber()
                != chapterNumber) {

            ensureChapterNumberAvailable(
                    chapterNumber
            );
        }
        
        if (!generatedSlug.equals(
                chapter.getSlug()
        )) {
            ensureSlugAvailable(
                    chapter,
                    generatedSlug
            );
        }

        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.updateDraft(
                chapterNumber,
                command.title(),
                generatedSlug,
                command.summary(),
                command.content(),
                command.actorId(),
                now
        );

        if (chapter.getAggregateVersion()
                == expectedVersion) {

            return ChapterDTOMapper.toDTO(
                    chapter
            );
        }

        Chapter savedChapter =
                chapterRepositoryPort.save(
                        chapter,
                        expectedVersion
                );

        chapterRevisionRecorder.record(
                savedChapter,
                ChapterRevisionChangeType.UPDATE_DRAFT,
                command.actorId(),
                null
        );

        return ChapterDTOMapper.toDTO(
                savedChapter
        );
    }

    private void validateChapterNumber(
            int chapterNumber
    ) {
        if (chapterNumber < 1) {
            throw new IllegalArgumentException(
                    "Số chương phải lớn hơn hoặc bằng 1."
            );
        }
    }

    private void ensureChapterNumberAvailable(
            int chapterNumber
    ) {
        if (chapterRepositoryPort
                .existsByChapterNumber(
                        chapterNumber
                )) {

            throw new ChapterNumberAlreadyExistsException(
                    chapterNumber
            );
        }
    }

    private void ensureSlugAvailable(
            Chapter currentChapter,
            Slug slug
    ) {
        Optional<Chapter> existingChapter =
                chapterRepositoryPort
                        .findBySlug(
                                slug
                        );

        boolean belongsToAnotherChapter =
                existingChapter.isPresent()
                        && !existingChapter
                        .orElseThrow()
                        .getId()
                        .equals(
                                currentChapter.getId()
                        );

        if (belongsToAnotherChapter) {
            throw new ChapterSlugAlreadyExistsException(
                    "Slug của chương đã tồn tại: "
                            + slug.value()
            );
        }
    }
}
