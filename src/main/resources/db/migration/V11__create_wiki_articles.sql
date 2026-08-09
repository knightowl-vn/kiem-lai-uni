CREATE TABLE wiki_articles (
    id CHAR(36) NOT NULL,

    title VARCHAR(200) NOT NULL,

    slug VARCHAR(180) NOT NULL,

    article_type VARCHAR(30) NOT NULL,

    summary VARCHAR(1000) NOT NULL DEFAULT '',

    content MEDIUMTEXT NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    created_by CHAR(36) NOT NULL,

    updated_by CHAR(36) DEFAULT NULL,

    published_by CHAR(36) DEFAULT NULL,

    archived_by CHAR(36) DEFAULT NULL,

    aggregate_version BIGINT NOT NULL DEFAULT 1,

    persistence_version BIGINT NOT NULL DEFAULT 0,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    published_at DATETIME(6) DEFAULT NULL,

    archived_at DATETIME(6) DEFAULT NULL,

    CONSTRAINT pk_wiki_articles
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_articles_type_slug
        UNIQUE (article_type, slug),

    CONSTRAINT chk_wiki_articles_title_length
        CHECK (
            CHAR_LENGTH(title) BETWEEN 2 AND 200
        ),

    CONSTRAINT chk_wiki_articles_slug_length
        CHECK (
            CHAR_LENGTH(slug) BETWEEN 1 AND 180
        ),

    CONSTRAINT chk_wiki_articles_article_type
        CHECK (
            article_type IN (
                'CHARACTER',
                'REALM',
                'CULTIVATION_PATH',
                'FACTION',
                'ITEM',
                'TECHNIQUE',
                'LOCATION',
                'WORLD',
                'TIMELINE_EVENT'
            )
        ),

    CONSTRAINT chk_wiki_articles_summary_length
        CHECK (
            CHAR_LENGTH(summary) <= 1000
        ),

    CONSTRAINT chk_wiki_articles_content_length
        CHECK (
            CHAR_LENGTH(content) <= 500000
        ),

    CONSTRAINT chk_wiki_articles_status
        CHECK (
            status IN (
                'DRAFT',
                'PUBLISHED',
                'ARCHIVED'
            )
        ),

    CONSTRAINT chk_wiki_articles_aggregate_version
        CHECK (
            aggregate_version >= 1
        ),

    CONSTRAINT chk_wiki_articles_persistence_version
        CHECK (
            persistence_version >= 0
        ),

    CONSTRAINT chk_wiki_articles_published_data
        CHECK (
            status <> 'PUBLISHED'
            OR (
                published_by IS NOT NULL
                AND published_at IS NOT NULL
            )
        ),

    CONSTRAINT chk_wiki_articles_archived_data
        CHECK (
            status <> 'ARCHIVED'
            OR (
                archived_by IS NOT NULL
                AND archived_at IS NOT NULL
            )
        ),

    KEY idx_wiki_articles_status (
        status
    ),

    KEY idx_wiki_articles_article_type (
        article_type
    ),

    KEY idx_wiki_articles_type_status (
        article_type,
        status
    ),

    KEY idx_wiki_articles_created_at (
        created_at
    ),

    KEY idx_wiki_articles_published_at (
        published_at
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;