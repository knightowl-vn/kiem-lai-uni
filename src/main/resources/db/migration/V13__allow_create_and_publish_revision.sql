ALTER TABLE wiki_article_revisions
    DROP CHECK chk_wiki_article_revisions_change_type;

ALTER TABLE wiki_article_revisions
    ADD CONSTRAINT chk_wiki_article_revisions_change_type
    CHECK (
        change_type IN (
            'CREATE_DRAFT',
            'CREATE_AND_PUBLISH',
            'UPDATE_DRAFT',
            'PUBLISH',
            'UPDATE_PUBLISHED',
            'ARCHIVE',
            'RESTORE'
        )
    );