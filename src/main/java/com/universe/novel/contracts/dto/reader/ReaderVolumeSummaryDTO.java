package com.universe.novel.contracts.dto.reader;

import java.util.UUID;

public record ReaderVolumeSummaryDTO(
        UUID id,
        String title,
        String slug,
        int sortOrder
) {
}
