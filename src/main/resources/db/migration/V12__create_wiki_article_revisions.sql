CREATE TABLE wiki_article_revisions (
    id CHAR(36) NOT NULL,

    article_id CHAR(36) NOT NULL,

    revision_number BIGINT NOT NULL,

    title VARCHAR(200) NOT NULL,

    slug VARCHAR(180) NOT NULL,

    article_type VARCHAR(30) NOT NULL,

    summary VARCHAR(1000) NOT NULL DEFAULT '',

    content MEDIUMTEXT NOT NULL,

    status VARCHAR(20) NOT NULL,

    change_type VARCHAR(30) NOT NULL,

    edit_summary VARCHAR(500) DEFAULT NULL,

    edited_by CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_article_revisions
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_article_revisions_article_number
        UNIQUE (
            article_id,
            revision_number
        ),

    CONSTRAINT fk_wiki_article_revisions_article
        FOREIGN KEY (article_id)
        REFERENCES wiki_articles (id)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT,

    CONSTRAINT chk_wiki_article_revisions_number
        CHECK (
            revision_number >= 1
        ),

    CONSTRAINT chk_wiki_article_revisions_title_length
        CHECK (
            CHAR_LENGTH(title)
                BETWEEN 2 AND 200
        ),

    CONSTRAINT chk_wiki_article_revisions_slug_length
        CHECK (
            CHAR_LENGTH(slug)
                BETWEEN 1 AND 180
        ),

    CONSTRAINT chk_wiki_article_revisions_article_type
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

    CONSTRAINT chk_wiki_article_revisions_summary_length
        CHECK (
            CHAR_LENGTH(summary) <= 1000
        ),

    CONSTRAINT chk_wiki_article_revisions_content_length
        CHECK (
            CHAR_LENGTH(content) <= 500000
        ),

    CONSTRAINT chk_wiki_article_revisions_status
        CHECK (
            status IN (
                'DRAFT',
                'PUBLISHED',
                'ARCHIVED'
            )
        ),

    CONSTRAINT chk_wiki_article_revisions_change_type
        CHECK (
            change_type IN (
                'CREATE_DRAFT',
                'UPDATE_DRAFT',
                'PUBLISH',
                'UPDATE_PUBLISHED',
                'ARCHIVE',
                'RESTORE'
            )
        ),

    CONSTRAINT chk_wiki_article_revisions_edit_summary
        CHECK (
            edit_summary IS NULL
            OR CHAR_LENGTH(edit_summary) <= 500
        ),

    KEY idx_wiki_article_revisions_article (
        article_id
    ),

    KEY idx_wiki_article_revisions_article_created (
        article_id,
        created_at
    ),

    KEY idx_wiki_article_revisions_editor (
        edited_by
    )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;