package com.universe.novel.application.profile;

public record UpdateNovelProfileCommand(
        String title,
        String author,
        String description,
        String status,
        NovelCoverUpload coverUpload
) {
}
