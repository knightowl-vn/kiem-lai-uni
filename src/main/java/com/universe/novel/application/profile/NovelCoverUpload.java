package com.universe.novel.application.profile;

import java.io.InputStream;

public record NovelCoverUpload(
        InputStream content,
        long sizeBytes,
        String contentType,
        String originalFilename
) {
}
