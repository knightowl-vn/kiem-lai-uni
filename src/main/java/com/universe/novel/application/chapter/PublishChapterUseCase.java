package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeNotPublishedException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class PublishChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    public PublishChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public ChapterDTO execute(
            PublishChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Publish chapter command không được để trống."
        );

        UUID chapterId =
                Objects.requireNonNull(
                        command.chapterId(),
                        "Chapter ID không được để trống."
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

        /*
         * Quan trọng:
         *
         * Lock chính Volume cha để Publish Chapter
         * và Archive Volume serialize trên cùng một row.
         */
        Volume parentVolume =
                volumeRepositoryPort
                        .findByIdForUpdate(
                                chapter.getVolumeId()
                        )
                        .orElseThrow(() ->
                                new VolumeNotFoundException(
                                        chapter.getVolumeId()
                                )
                        );

        ensureParentVolumePublished(
                parentVolume
        );

        /*
         * Chapter optimistic locking vẫn giữ nguyên.
         */
        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.publish(
                command.actorId(),
                now
        );

        Chapter savedChapter =
                chapterRepositoryPort.save(
                        chapter,
                        expectedVersion
                );

        return ChapterDTOMapper.toDTO(
                savedChapter
        );
    }

    private void ensureParentVolumePublished(
            Volume volume
    ) {
        if (volume.getStatus()
                != VolumeStatus.PUBLISHED) {

            throw new VolumeNotPublishedException(
                    volume.getId()
            );
        }
    }
}