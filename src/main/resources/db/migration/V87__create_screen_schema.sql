-- =========================================================
-- V87__create_screen_schema.sql
--
-- Screen Core Persistence Foundation (MS-08B1)
-- Schema for Screen franchises, titles, franchise memberships, and playable entries.
-- =========================================================

-- 1. Screen Franchises: Grouping hierarchy across installments
CREATE TABLE screen_franchises (
    id CHAR(36) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    name TEXT NOT NULL,

    CONSTRAINT pk_screen_franchises
        PRIMARY KEY (id),

    CONSTRAINT uq_screen_franchises_slug
        UNIQUE (slug),

    CONSTRAINT chk_screen_franchises_slug_length
        CHECK (CHAR_LENGTH(TRIM(slug)) > 0 AND CHAR_LENGTH(slug) <= 180),

    CONSTRAINT chk_screen_franchises_name
        CHECK (CHAR_LENGTH(TRIM(name)) > 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- 2. Screen Titles: Independent audiovisual catalog titles
CREATE TABLE screen_titles (
    id CHAR(36) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    title TEXT NOT NULL,
    original_title TEXT NULL,
    synopsis TEXT NULL,
    structure VARCHAR(20) NOT NULL,
    presentation VARCHAR(20) NOT NULL,
    poster_media_asset_id CHAR(36) NULL,
    backdrop_media_asset_id CHAR(36) NULL,
    status VARCHAR(20) NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_screen_titles
        PRIMARY KEY (id),

    CONSTRAINT uq_screen_titles_slug
        UNIQUE (slug),

    CONSTRAINT chk_screen_titles_slug_length
        CHECK (CHAR_LENGTH(TRIM(slug)) > 0 AND CHAR_LENGTH(slug) <= 180),

    CONSTRAINT chk_screen_titles_title
        CHECK (CHAR_LENGTH(TRIM(title)) > 0),

    CONSTRAINT chk_screen_titles_original_title
        CHECK (original_title IS NULL OR CHAR_LENGTH(TRIM(original_title)) > 0),

    CONSTRAINT chk_screen_titles_structure
        CHECK (structure IN ('SERIES', 'FEATURE')),

    CONSTRAINT chk_screen_titles_presentation
        CHECK (presentation IN ('ANIMATION_3D', 'LIVE_ACTION')),

    CONSTRAINT chk_screen_titles_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),

    CONSTRAINT chk_screen_titles_publication_state
        CHECK (
            (status = 'DRAFT' AND published_at IS NULL)
            OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
            OR (status = 'ARCHIVED')
        ),

    CONSTRAINT chk_screen_titles_timestamps
        CHECK (
            updated_at >= created_at
            AND (published_at IS NULL OR (published_at >= created_at AND updated_at >= published_at))
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;


-- 3. Screen Franchise Memberships: Ordered association between franchises and member titles
CREATE TABLE screen_franchise_memberships (
    id CHAR(36) NOT NULL,
    franchise_id CHAR(36) NOT NULL,
    title_id CHAR(36) NOT NULL,
    display_order INT NOT NULL,

    CONSTRAINT pk_screen_franchise_memberships
        PRIMARY KEY (id),

    CONSTRAINT fk_screen_franchise_memberships_franchise
        FOREIGN KEY (franchise_id)
        REFERENCES screen_franchises (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT fk_screen_franchise_memberships_title
        FOREIGN KEY (title_id)
        REFERENCES screen_titles (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT uq_screen_franchise_memberships_franchise_title
        UNIQUE (franchise_id, title_id),

    CONSTRAINT chk_screen_franchise_memberships_display_order
        CHECK (display_order >= 0)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_screen_franchise_memberships_title
    ON screen_franchise_memberships (title_id);


-- 4. Screen Entries: Individual playable audiovisual entries
CREATE TABLE screen_entries (
    id CHAR(36) NOT NULL,
    title_id CHAR(36) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    type VARCHAR(20) NOT NULL,
    number INT NULL,
    title TEXT NOT NULL,
    synopsis TEXT NULL,
    video_media_asset_id CHAR(36) NULL,
    thumbnail_media_asset_id CHAR(36) NULL,
    duration_seconds BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_screen_entries
        PRIMARY KEY (id),

    CONSTRAINT fk_screen_entries_title
        FOREIGN KEY (title_id)
        REFERENCES screen_titles (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_screen_entries_title_slug
        UNIQUE (title_id, slug),

    CONSTRAINT chk_screen_entries_slug_length
        CHECK (CHAR_LENGTH(TRIM(slug)) > 0 AND CHAR_LENGTH(slug) <= 180),

    CONSTRAINT chk_screen_entries_type
        CHECK (type IN ('EPISODE', 'FEATURE', 'SPECIAL', 'TRAILER')),

    CONSTRAINT chk_screen_entries_number_for_type
        CHECK (
            (type = 'EPISODE' AND number IS NOT NULL AND number > 0)
            OR (type = 'FEATURE' AND number IS NULL)
            OR (type IN ('SPECIAL', 'TRAILER') AND (number IS NULL OR number > 0))
        ),

    CONSTRAINT chk_screen_entries_title
        CHECK (CHAR_LENGTH(TRIM(title)) > 0),

    CONSTRAINT chk_screen_entries_duration
        CHECK (duration_seconds IS NULL OR duration_seconds > 0),

    CONSTRAINT chk_screen_entries_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),

    CONSTRAINT chk_screen_entries_publication_state
        CHECK (
            (status = 'DRAFT' AND published_at IS NULL)
            OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
            OR (status = 'ARCHIVED')
        ),

    CONSTRAINT chk_screen_entries_timestamps
        CHECK (
            updated_at >= created_at
            AND (published_at IS NULL OR (published_at >= created_at AND updated_at >= published_at))
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
