-- =========================================================
-- V27__create_novel_chapter_bookmarks.sql
--
-- Novel Chapter Bookmarks Persistence Foundation
-- =========================================================

CREATE TABLE novel_chapter_bookmarks (
    id CHAR(36) NOT NULL,

    user_id CHAR(36) NOT NULL,

    chapter_id CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_chapter_bookmarks
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_chapter_bookmarks_user_chapter
        UNIQUE (user_id, chapter_id),

    CONSTRAINT fk_novel_chapter_bookmarks_chapter
        FOREIGN KEY (chapter_id)
        REFERENCES novel_chapters (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
