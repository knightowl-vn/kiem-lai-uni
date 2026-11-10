-- =================================================================================
-- V99: Add Screen Outro End Boundary (MS-08G3-UX02-C2.1)
-- Adds optional default_outro_end_seconds to screen_titles and
-- optional outro_end_seconds to screen_entries.
-- Forward-only, additive migration safe for existing rows (all new columns nullable).
-- =================================================================================

ALTER TABLE screen_titles
    ADD COLUMN default_outro_end_seconds BIGINT NULL AFTER default_outro_start_seconds,
    ADD CONSTRAINT chk_screen_titles_default_outro_interval CHECK (
        (default_outro_end_seconds IS NULL)
        OR (
            default_outro_start_seconds IS NOT NULL
            AND default_outro_end_seconds > default_outro_start_seconds
        )
    );

ALTER TABLE screen_entries
    ADD COLUMN outro_end_seconds BIGINT NULL AFTER outro_start_seconds,
    ADD CONSTRAINT chk_screen_entries_outro_interval CHECK (
        (
            outro_mode IN ('INHERIT', 'DISABLED')
            AND outro_end_seconds IS NULL
        )
        OR (
            outro_mode = 'OVERRIDE'
            AND (
                outro_end_seconds IS NULL
                OR (
                    outro_start_seconds IS NOT NULL
                    AND outro_end_seconds > outro_start_seconds
                )
            )
        )
    );
