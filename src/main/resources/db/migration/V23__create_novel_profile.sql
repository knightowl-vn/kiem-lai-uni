CREATE TABLE novel_profile (

    id CHAR(36) NOT NULL,

    title VARCHAR(200) NOT NULL,

    slug VARCHAR(180) NOT NULL,

    author VARCHAR(200) NOT NULL,

    description TEXT NOT NULL,

    cover_image_url VARCHAR(500) DEFAULT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'ONGOING',

    created_at DATETIME(6) NOT NULL,

    updated_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_novel_profile
        PRIMARY KEY (id),

    CONSTRAINT uq_novel_profile_slug
        UNIQUE (slug),

    CONSTRAINT chk_novel_profile_title_length
        CHECK (
            CHAR_LENGTH(title) BETWEEN 1 AND 200
        ),

    CONSTRAINT chk_novel_profile_slug_length
        CHECK (
            CHAR_LENGTH(slug) BETWEEN 1 AND 180
        ),

    CONSTRAINT chk_novel_profile_author_length
        CHECK (
            CHAR_LENGTH(author) BETWEEN 1 AND 200
        ),

    CONSTRAINT chk_novel_profile_description_length
        CHECK (
            CHAR_LENGTH(description) <= 10000
        ),

    CONSTRAINT chk_novel_profile_cover_image_url_length
        CHECK (
            cover_image_url IS NULL
            OR CHAR_LENGTH(cover_image_url) <= 500
        ),

    CONSTRAINT chk_novel_profile_status
        CHECK (
            status IN (
                'ONGOING',
                'COMPLETED',
                'HIATUS'
            )
        )

)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;