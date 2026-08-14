CREATE TABLE wiki_images (
    id CHAR(36) NOT NULL,

    content_hash CHAR(64) NOT NULL,

    public_id VARCHAR(255) NOT NULL,

    url VARCHAR(1000) NOT NULL,

    source_content_type VARCHAR(100) NOT NULL,

    size_bytes BIGINT NOT NULL,

    created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_wiki_images
        PRIMARY KEY (id),

    CONSTRAINT uq_wiki_images_content_hash
        UNIQUE (content_hash),

    CONSTRAINT uq_wiki_images_public_id
        UNIQUE (public_id)
);

CREATE INDEX idx_wiki_images_created_at
    ON wiki_images(created_at);