-- =========================================================
-- V79__add_report_evidence_media_asset.sql
--
-- Community Post Moderation Evidence Retention (MS-07B8.5.1)
-- - Adds evidence_media_asset_id to interaction_reports
-- - Preserves scalar reference to attached MediaAsset without cross-context FK
-- =========================================================

ALTER TABLE interaction_reports
    ADD COLUMN evidence_media_asset_id CHAR(36) NULL AFTER content_snapshot;
