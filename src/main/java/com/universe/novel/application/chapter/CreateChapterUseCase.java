package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterSortOrderAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreateChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public CreateChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public ChapterDTO execute(
            CreateChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Create chapter command không được để trống."
        );

        UUID volumeId =
                Objects.requireNonNull(
                        command.volumeId(),
                        "Volume ID không được để trống."
                );

        /*
         * Cross-aggregate validation:
         * Volume cha phải tồn tại.
         */
        ensureVolumeExists(
                volumeId
        );

        Slug slug =
                new Slug(
                        command.slug()
                );

        ensureSlugAvailable(
                slug
        );

        ensureSortOrderAvailable(
                volumeId,
                command.sortOrder()
        );

        UUID chapterId =
                idGeneratorPort.generate();

        Instant now =
                clockPort.now();

        Chapter chapter =
                Chapter.createDraft(
                        chapterId,
                        volumeId,
                        command.chapterNumber(),
                        command.sortOrder(),
                        command.title(),
                        slug,
                        command.summary(),
                        command.content(),
                        command.actorId(),
                        now
                );

        Chapter savedChapter =
                chapterRepositoryPort.save(
                        chapter,
                        0L
                );

        return ChapterDTOMapper.toDTO(
                savedChapter
        );
    }

    private void ensureVolumeExists(
            UUID volumeId
    ) {
        if (volumeRepositoryPort
                .findById(
                        volumeId
                )
                .isEmpty()) {

            throw new VolumeNotFoundException(
                    volumeId
            );
        }
    }

    private void ensureSlugAvailable(
            Slug slug
    ) {
        if (chapterRepositoryPort
                .existsBySlug(
                        slug
                )) {

            throw new ChapterSlugAlreadyExistsException(
                    "Slug của chương đã tồn tại: "
                            + slug.value()
            );
        }
    }

    private void ensureSortOrderAvailable(
            UUID volumeId,
            int sortOrder
    ) {
        if (chapterRepositoryPort
                .existsByVolumeIdAndSortOrder(
                        volumeId,
                        sortOrder
                )) {

            throw new ChapterSortOrderAlreadyExistsException(
                    volumeId,
                    sortOrder
            );
        }
    }
}