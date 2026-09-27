-- =========================================================
-- V64__add_wiki_cover_orphan_deleting_state.sql
--
-- Wiki Cover Orphan Durable Deletion Fence Foundation (MS-05G8C5.2)
-- Allows 'DELETING' status to represent an atomic, durable deletion fence
-- preventing TOCTOU races between reconciliation and attachment.
-- =========================================================

ALTER TABLE wiki_cover_orphans
    DROP CHECK chk_wiki_cover_orphans_status;

ALTER TABLE wiki_cover_orphans
    ADD CONSTRAINT chk_wiki_cover_orphans_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'DELETING'));
