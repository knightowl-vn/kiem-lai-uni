CREATE TABLE novel_chapters (
    id CHAR(36) NOT NULL,

    volume_id CHAR(36) NOT NULL,

    chapter_number INT DEFAULT NULL,

    sort_order INT NOT NULL,

    title VARCHAR(250) NOT NULL,

    slug VARCHAR(180) NOT NULL,

    summary VARCHAR(1000) NOT NULL DEFAULT '',

    content MEDIUMTEXT NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    created_by CHAR(36) NOT NULL,

    updated_by CHAR(36) NOT NULL,

    published_by CHAR(36) DEFAULT NULL,

    archived_by CHAR(36) DEFAULT NULL,

    aggregate_version BIGINT NOT NULL DEFAULT 1,

    persistence_version BIGINT NOT NULL DEFAULT 0,

    content_version BIGINT NOT NULL DEFAULT 1,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    published_at DATETIME(6) DEFAULT NULL,

    archived_at DATETIME(6) DEFAULT NULL,

    CONSTRAINT pk_novel_chapters
        PRIMARY KEY (id),

    CONSTRAINT fk_novel_chapters_volume
        FOREIGN KEY (volume_id)
        REFERENCES novel_volumes (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT uq_novel_chapters_slug
        UNIQUE (slug),

    CONSTRAINT uq_novel_chapters_volume_sort_order
        UNIQUE (
            volume_id,
            sort_order
        ),

    CONSTRAINT chk_novel_chapters_chapter_number
        CHECK (
            chapter_number IS NULL
            OR chapter_number >= 1
        ),

    CONSTRAINT chk_novel_chapters_sort_order
        CHECK (
            sort_order >= 1
        ),

    CONSTRAINT chk_novel_chapters_title_length
        CHECK (
            CHAR_LENGTH(title) BETWEEN 2 AND 250
        ),

    CONSTRAINT chk_novel_chapters_slug_length
        CHECK (
            CHAR_LENGTH(slug) BETWEEN 1 AND 180
        ),

    CONSTRAINT chk_novel_chapters_summary_length
        CHECK (
            CHAR_LENGTH(summary) <= 1000
        ),

    CONSTRAINT chk_novel_chapters_content_length
        CHECK (
            CHAR_LENGTH(content) <= 500000
        ),

    CONSTRAINT chk_novel_chapters_status
        CHECK (
            status IN (
                'DRAFT',
                'PUBLISHED',
                'ARCHIVED'
            )
        ),

    CONSTRAINT chk_novel_chapters_aggregate_version
        CHECK (
            aggregate_version >= 1
        ),

    CONSTRAINT chk_novel_chapters_persistence_version
        CHECK (
            persistence_version >= 0
        ),

    CONSTRAINT chk_novel_chapters_content_version
        CHECK (
            content_version >= 1
        ),

    CONSTRAINT chk_novel_chapters_publish_audit_pair
        CHECK (
            (
                published_by IS NULL
                AND published_at IS NULL
            )
            OR
            (
                published_by IS NOT NULL
                AND published_at IS NOT NULL
            )
        ),

    CONSTRAINT chk_novel_chapters_lifecycle_audit
        CHECK (
            (
                status = 'DRAFT'
                AND published_by IS NULL
                AND published_at IS NULL
                AND archived_by IS NULL
                AND archived_at IS NULL
            )
            OR
            (
                status = 'PUBLISHED'
                AND published_by IS NOT NULL
                AND published_at IS NOT NULL
                AND archived_by IS NULL
                AND archived_at IS NULL
            )
            OR
            (
                status = 'ARCHIVED'
                AND archived_by IS NOT NULL
                AND archived_at IS NOT NULL
            )
        ),

    KEY idx_novel_chapters_volume_status (
        volume_id,
        status
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
