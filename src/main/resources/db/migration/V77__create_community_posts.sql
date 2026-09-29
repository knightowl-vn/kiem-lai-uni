-- =========================================================
-- V77__create_community_posts.sql
--
-- Community Post Core Persistence Foundation (MS-07B2)
-- Schema for Community posts and immutable edit revision history.
-- =========================================================

CREATE TABLE community_posts (
    id CHAR(36) NOT NULL,
    author_user_id CHAR(36) NOT NULL,
    caption TEXT NOT NULL,
    image_media_asset_id CHAR(36) NULL,
    content_version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_community_posts
        PRIMARY KEY (id),

    CONSTRAINT chk_community_posts_caption
        CHECK (CHAR_LENGTH(TRIM(caption)) > 0 AND CHAR_LENGTH(caption) <= 2000),

    CONSTRAINT chk_community_posts_content_version
        CHECK (content_version >= 0),

    CONSTRAINT chk_community_posts_updated_at
        CHECK (updated_at >= created_at)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_community_posts_feed_created
    ON community_posts (created_at DESC, id DESC);

CREATE INDEX idx_community_posts_author_feed
    ON community_posts (author_user_id, created_at DESC, id DESC);

CREATE TABLE community_post_revisions (
    id CHAR(36) NOT NULL,
    post_id CHAR(36) NOT NULL,
    revision_number INT NOT NULL,
    editor_user_id CHAR(36) NOT NULL,
    previous_caption TEXT NOT NULL,
    caption TEXT NOT NULL,
    edited_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_community_post_revisions
        PRIMARY KEY (id),

    CONSTRAINT fk_community_post_revisions_post
        FOREIGN KEY (post_id)
        REFERENCES community_posts (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT uq_community_post_revisions_post_number
        UNIQUE (post_id, revision_number),

    CONSTRAINT chk_community_post_revisions_number
        CHECK (revision_number >= 1),

    CONSTRAINT chk_community_post_revisions_prev_caption
        CHECK (CHAR_LENGTH(TRIM(previous_caption)) > 0 AND CHAR_LENGTH(previous_caption) <= 2000),

    CONSTRAINT chk_community_post_revisions_caption
        CHECK (CHAR_LENGTH(TRIM(caption)) > 0 AND CHAR_LENGTH(caption) <= 2000)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
