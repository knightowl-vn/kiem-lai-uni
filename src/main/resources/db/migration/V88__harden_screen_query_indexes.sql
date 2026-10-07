-- =========================================================
-- V88__harden_screen_query_indexes.sql
--
-- Screen Query Semantics & MySQL Hardening (MS-08B3)
-- Hardening indexes for deterministic membership ordering and publication filtering.
-- =========================================================

-- 1. Index on screen_franchise_memberships (franchise_id, display_order)
-- Supports deterministic display_order ordering for franchise member queries without filesort.
CREATE INDEX idx_screen_franchise_memberships_franchise_display_order
    ON screen_franchise_memberships (franchise_id, display_order);

-- 2. Index on screen_entries (title_id, status, type, number)
-- Supports fail-closed publication-filter access and deterministic series episode navigation without filesort.
CREATE INDEX idx_screen_entries_title_status_type_number
    ON screen_entries (title_id, status, type, number);
