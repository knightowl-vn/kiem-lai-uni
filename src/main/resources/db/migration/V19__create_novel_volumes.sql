CREATE TABLE novel_volumes (
    id CHAR(36) NOT NULL,

    title VARCHAR(200) NOT NULL,

    slug VARCHAR(180) NOT NULL,

    description VARCHAR(1000) NOT NULL DEFAULT '',

    sort_order INT NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    created_by CHAR(36) NOT NULL,

    updated_by CHAR(36) NOT NULL,

    published_by CHAR(36) DEFAULT NULL,

    archived_by CHAR(36) DEFAULT NULL,

    aggregate_version BIGINT NOT NULL DEFAULT 1,

    persistence_version BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    published_at DATETIME(6) DEFAULT NULL,

    archived_at DATETIME(6) DEFAULT NULL,

    CONSTRAINT pk_novel_volumes
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_volumes_slug
        UNIQUE (slug),

    CONSTRAINT uq_novel_volumes_sort_order
        UNIQUE (sort_order),

    CONSTRAINT chk_novel_volumes_title_length
        CHECK (
            CHAR_LENGTH(title) BETWEEN 2 AND 200
        ),

    CONSTRAINT chk_novel_volumes_slug_length
        CHECK (
            CHAR_LENGTH(slug) BETWEEN 1 AND 180
        ),

    CONSTRAINT chk_novel_volumes_description_length
        CHECK (
            CHAR_LENGTH(description) <= 1000
        ),

    CONSTRAINT chk_novel_volumes_sort_order
        CHECK (
            sort_order >= 1
        ),

    CONSTRAINT chk_novel_volumes_status
        CHECK (
            status IN (
                'DRAFT',
                'PUBLISHED',
                'ARCHIVED'
            )
        ),

    CONSTRAINT chk_novel_volumes_aggregate_version
        CHECK (
            aggregate_version >= 1
        ),

    CONSTRAINT chk_novel_volumes_persistence_version
        CHECK (
            persistence_version >= 0
        ),

    CONSTRAINT chk_novel_volumes_publish_audit_pair
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

    CONSTRAINT chk_novel_volumes_lifecycle_audit
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

    KEY idx_novel_volumes_status (
        status
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
