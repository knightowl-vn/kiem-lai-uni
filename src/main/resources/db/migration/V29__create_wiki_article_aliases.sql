-- =========================================================
-- V29__create_wiki_article_aliases.sql
--
-- Wiki Article Aliases Persistence Foundation
-- =========================================================

CREATE TABLE wiki_article_aliases (
    id CHAR(36) NOT NULL,

    article_id CHAR(36) NOT NULL,

    alias VARCHAR(200) NOT NULL,

    normalized_alias VARCHAR(200) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_article_aliases
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_article_aliases_article_alias
        UNIQUE (article_id, normalized_alias),

    CONSTRAINT fk_wiki_article_aliases_article
        FOREIGN KEY (article_id)
        REFERENCES wiki_articles (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE,

    CONSTRAINT chk_wiki_article_aliases_alias_non_blank
        CHECK (
            CHAR_LENGTH(TRIM(alias)) >= 1
        )
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_wiki_article_aliases_normalized
    ON wiki_article_aliases (normalized_alias);