ALTER TABLE novel_chapters
    ADD CONSTRAINT uq_novel_chapters_chapter_number
    UNIQUE (chapter_number);
