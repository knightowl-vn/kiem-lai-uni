package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetChapterDetailUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    public GetChapterDetailUseCase(
            ChapterRepositoryPort chapterRepositoryPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;
    }

    @Transactional(readOnly = true)
    public ChapterDTO execute(
            UUID chapterId
    ) {
        Objects.requireNonNull(
                chapterId,
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

        return ChapterDTOMapper.toDTO(
                chapter
        );
    }
}