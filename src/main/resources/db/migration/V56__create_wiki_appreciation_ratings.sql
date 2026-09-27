-- =========================================================
-- V56__create_wiki_appreciation_ratings.sql
--
-- Wiki Appreciation Ratings Persistence Foundation (MS-05F2)
-- Authoritative appreciation rating mechanism for eligible Wiki articles.
-- =========================================================

CREATE TABLE wiki_appreciation_ratings (
    id CHAR(36) NOT NULL,

    wiki_article_id CHAR(36) NOT NULL,

    user_id CHAR(36) NOT NULL,

    value TINYINT UNSIGNED NOT NULL,

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_appreciation_ratings
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_appreciation_ratings_article_user
        UNIQUE (wiki_article_id, user_id),

    CONSTRAINT chk_wiki_appreciation_ratings_value
        CHECK (value BETWEEN 1 AND 5),

    CONSTRAINT fk_wiki_appreciation_ratings_article
        FOREIGN KEY (wiki_article_id)
        REFERENCES wiki_articles (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
