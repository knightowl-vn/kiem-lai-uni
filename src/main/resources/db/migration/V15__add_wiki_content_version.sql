-- =========================================================
-- V15__add_wiki_content_version.sql
--
-- Tách:
--
-- aggregate_version
-- = version nghiệp vụ của Aggregate
--
-- content_version
-- = phiên bản nội dung mà Admin nhìn thấy
-- =========================================================


-- =========================================================
-- 1. ARTICLE
-- =========================================================

ALTER TABLE wiki_articles
    ADD COLUMN content_version BIGINT NULL
    AFTER aggregate_version;


-- =========================================================
-- 2. REVISION
-- =========================================================

ALTER TABLE wiki_article_revisions
    ADD COLUMN content_version BIGINT NULL
    AFTER revision_number;


-- =========================================================
-- 3. TEMP TABLE
--
-- Aiven bật sql_require_primary_key nên temporary table
-- cũng phải có Primary Key.
--
-- Đồng thời quy định rõ charset/collation để không phụ thuộc
-- default collation của MySQL 8.4.
-- =========================================================

CREATE TEMPORARY TABLE tmp_wiki_revision_content_versions (

    id CHAR(36)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci
        NOT NULL,

    calculated_content_version BIGINT NOT NULL,

    CONSTRAINT pk_tmp_wiki_revision_content_versions
        PRIMARY KEY (id)

)
ENGINE = InnoDB
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;


-- =========================================================
-- 4. TÍNH CONTENT VERSION CỦA REVISION CŨ
--
-- Những event làm thay đổi nội dung:
--
-- CREATE_DRAFT
-- CREATE_AND_PUBLISH
-- UPDATE_DRAFT
-- UPDATE_PUBLISHED
-- RESTORE
--
-- Những event lifecycle:
--
-- PUBLISH
-- UNPUBLISH
-- ARCHIVE
--
-- không tăng content version.
-- =========================================================

INSERT INTO tmp_wiki_revision_content_versions (
    id,
    calculated_content_version
)
SELECT
    id,

    GREATEST(
        1,

        SUM(
            CASE
                WHEN change_type IN (
                    'CREATE_DRAFT',
                    'CREATE_AND_PUBLISH',
                    'UPDATE_DRAFT',
                    'UPDATE_PUBLISHED',
                    'RESTORE'
                )
                THEN 1
                ELSE 0
            END
        ) OVER (
            PARTITION BY article_id

            ORDER BY revision_number

            ROWS BETWEEN
                UNBOUNDED PRECEDING
                AND CURRENT ROW
        )
    )

FROM wiki_article_revisions;


-- =========================================================
-- 5. BACKFILL CONTENT VERSION CHO REVISION
--
-- BINARY được dùng ở phép so sánh UUID để tránh lỗi:
--
-- utf8mb4_0900_ai_ci
-- vs
-- utf8mb4_unicode_ci
-- =========================================================

UPDATE wiki_article_revisions revision

JOIN tmp_wiki_revision_content_versions calculated

    ON BINARY calculated.id =
       BINARY revision.id

SET revision.content_version =
        calculated.calculated_content_version;


-- =========================================================
-- 6. XÓA TEMP TABLE
-- =========================================================

DROP TEMPORARY TABLE
    tmp_wiki_revision_content_versions;


-- =========================================================
-- 7. BACKFILL CONTENT VERSION CHO ARTICLE
--
-- Lấy content version lớn nhất trong lịch sử revision.
--
-- BINARY cũng được dùng ở article_id = id để tránh
-- khác collation giữa hai bảng cũ.
-- =========================================================

UPDATE wiki_articles article

LEFT JOIN (

    SELECT
        article_id,
        MAX(content_version)
            AS latest_content_version

    FROM wiki_article_revisions

    GROUP BY article_id

) revision_version

    ON BINARY revision_version.article_id =
       BINARY article.id

SET article.content_version =
        COALESCE(
            revision_version.latest_content_version,
            1
        );


-- =========================================================
-- 8. CHUYỂN SANG NOT NULL
-- =========================================================

ALTER TABLE wiki_articles
    MODIFY content_version
        BIGINT NOT NULL DEFAULT 1;


ALTER TABLE wiki_article_revisions
    MODIFY content_version
        BIGINT NOT NULL;


-- =========================================================
-- 9. CHECK CONSTRAINT
-- =========================================================

ALTER TABLE wiki_articles
    ADD CONSTRAINT
        chk_wiki_articles_content_version
    CHECK (
        content_version >= 1
    );


ALTER TABLE wiki_article_revisions
    ADD CONSTRAINT
        chk_wiki_article_revisions_content_version
    CHECK (
        content_version >= 1
    );