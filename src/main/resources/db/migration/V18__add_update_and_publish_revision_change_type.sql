ALTER TABLE wiki_article_revisions
    DROP CHECK chk_wiki_article_revisions_change_type;


ALTER TABLE wiki_article_revisions
    ADD CONSTRAINT chk_wiki_article_revisions_change_type
    CHECK (
        change_type IN (
            'CREATE_DRAFT',
            'UPDATE_DRAFT',
            'UPDATE_AND_PUBLISH',
            'CREATE_AND_PUBLISH',
            'PUBLISH',
            'UNPUBLISH',
            'UPDATE_PUBLISHED',
            'ARCHIVE',
            'RESTORE'
        )
    );