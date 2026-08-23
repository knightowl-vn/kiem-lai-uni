package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNumberAlreadyExistsException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
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

        int chapterNumber =
                Objects.requireNonNull(
                        command.chapterNumber(),
                        "Số chương không được để trống."
                );

        validateChapterNumber(
                chapterNumber
        );

        Volume volume =
                volumeRepositoryPort
                        .findById(
                                volumeId
                        )
                        .orElseThrow(() ->
                                new VolumeNotFoundException(
                                        volumeId
                                )
                        );

        Slug slug =
                ChapterSlugGenerator.generate(
                        volume,
                        chapterNumber
                );

        ensureChapterNumberAvailable(
                chapterNumber
        );

        ensureSlugAvailable(
                slug
        );


        UUID chapterId =
                idGeneratorPort.generate();

        Instant now =
                clockPort.now();

        Chapter chapter =
                Chapter.createDraft(
                        chapterId,
                        volumeId,
                        chapterNumber,
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
}
