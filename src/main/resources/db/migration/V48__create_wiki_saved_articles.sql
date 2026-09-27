-- =========================================================
-- V48__create_wiki_saved_articles.sql
--
-- Wiki Saved Articles Persistence Foundation
-- =========================================================

CREATE TABLE wiki_saved_articles (
    id CHAR(36) NOT NULL,

    user_id CHAR(36) NOT NULL,

    article_id CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_wiki_saved_articles
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_saved_articles_user_article
        UNIQUE (user_id, article_id),

    CONSTRAINT fk_wiki_saved_articles_article
        FOREIGN KEY (article_id)
        REFERENCES wiki_articles (id)
        ON UPDATE RESTRICT
        ON DELETE CASCADE
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_wiki_saved_articles_user_created_id
    ON wiki_saved_articles (user_id, created_at DESC, id DESC);
