/*
 * =========================================================
 * NORMALIZE WIKI IMAGE ID COLLATION
 * =========================================================
 *
 * wiki_articles.id và wiki_article_revisions.id hiện dùng:
 *
 * utf8mb4_unicode_ci
 *
 * wiki_images được tạo sau với database default:
 *
 * utf8mb4_0900_ai_ci
 *
 * Chuẩn hóa UUID của Wiki trước khi tạo foreign key.
 */
ALTER TABLE wiki_images
    MODIFY COLUMN id CHAR(36)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci
        NOT NULL;


/*
 * =========================================================
 * CURRENT ARTICLE → IMAGE REFERENCES
 * =========================================================
 */

CREATE TABLE wiki_article_image_references (
    id CHAR(36) NOT NULL,

    article_id CHAR(36) NOT NULL,

    image_id CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_wiki_article_image_references
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_article_image_references_article_image
        UNIQUE (article_id, image_id),

    CONSTRAINT fk_wiki_article_image_references_article
        FOREIGN KEY (article_id)
        REFERENCES wiki_articles(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_wiki_article_image_references_image
        FOREIGN KEY (image_id)
        REFERENCES wiki_images(id)
        ON DELETE RESTRICT
)
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;


CREATE INDEX idx_wiki_article_image_references_image
    ON wiki_article_image_references(image_id);


/*
 * =========================================================
 * REVISION → IMAGE REFERENCES
 * =========================================================
 */

CREATE TABLE wiki_revision_image_references (
    id CHAR(36) NOT NULL,

    revision_id CHAR(36) NOT NULL,

    image_id CHAR(36) NOT NULL,

    created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_wiki_revision_image_references
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_revision_image_references_revision_image
        UNIQUE (revision_id, image_id),

    CONSTRAINT fk_wiki_revision_image_references_revision
        FOREIGN KEY (revision_id)
        REFERENCES wiki_article_revisions(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_wiki_revision_image_references_image
        FOREIGN KEY (image_id)
        REFERENCES wiki_images(id)
        ON DELETE RESTRICT
)
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;


CREATE INDEX idx_wiki_revision_image_references_image
    ON wiki_revision_image_references(image_id);