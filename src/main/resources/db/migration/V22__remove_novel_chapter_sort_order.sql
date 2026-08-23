ALTER TABLE novel_chapters
    DROP INDEX uq_novel_chapters_volume_sort_order;

ALTER TABLE novel_chapters
    DROP CHECK chk_novel_chapters_sort_order;

ALTER TABLE novel_chapters
    DROP COLUMN sort_order;