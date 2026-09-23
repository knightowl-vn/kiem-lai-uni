-- =========================================================
-- V58__backfill_wiki_appreciation_half_star_units.sql
-- Deterministically backfill half-star units from untouched source
-- =========================================================

UPDATE wiki_appreciation_ratings
SET half_star_units = value * 2;
