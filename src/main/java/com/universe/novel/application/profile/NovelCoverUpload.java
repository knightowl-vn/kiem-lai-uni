package com.universe.novel.application.profile;

public record NovelCoverUpload(
        String originalFilename,
        String contentType,
        byte[] content
) {
}
