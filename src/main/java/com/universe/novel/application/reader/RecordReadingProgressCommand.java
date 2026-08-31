package com.universe.novel.application.reader;

import java.util.Objects;
import java.util.UUID;

public record RecordReadingProgressCommand(
        UUID userId,
        UUID chapterId
) {

    public RecordReadingProgressCommand {
        Objects.requireNonNull(
                userId,
                "ID người dùng không được để trống."
        );
        Objects.requireNonNull(
                chapterId,
                "ID chương không được để trống."
        );
    }
}
