-- =========================================================
-- V55__add_report_resolved_index.sql
--
-- Index on interaction_reports (resolved_at, id) for
-- retention candidate range/order and processed report history.
-- =========================================================

CREATE INDEX idx_interaction_reports_resolved_at_id
    ON interaction_reports (resolved_at, id);
