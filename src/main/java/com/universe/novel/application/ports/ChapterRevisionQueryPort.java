package com.universe.novel.application.ports;

import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;

import java.util.Optional;
import java.util.UUID;

public interface ChapterRevisionQueryPort {

    ChapterRevisionListPageDTO listRevisions(
            UUID chapterId,
            int page,
            int size
    );

    Optional<ChapterRevisionDetailDTO> getRevisionDetail(
            UUID chapterId,
            long revisionNumber
    );
}
