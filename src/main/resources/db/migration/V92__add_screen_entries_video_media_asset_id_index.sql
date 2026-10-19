-- =========================================================
-- V92__add_screen_entries_video_media_asset_id_index.sql
--
-- Screen Video MediaAsset Reference Index (MS-08E2-G3C-S2)
-- Supports current-read locking and reference checks on screen_entries(video_media_asset_id)
-- without next-key table-level lock escalation under MySQL REPEATABLE READ.
-- =========================================================

CREATE INDEX idx_screen_entries_video_media_asset_id
    ON screen_entries (video_media_asset_id);
