-- =================================================================================
-- V98: Add Screen Timeline Metadata (MS-08G3-UX02-C1)
-- Adds optional title-level default Intro/Outro timestamps on screen_titles and
-- episode-level Intro/Outro mode (INHERIT, OVERRIDE, DISABLED) + override timestamps
-- on screen_entries.
-- Forward-only, additive migration safe for existing rows.
-- =================================================================================

ALTER TABLE screen_titles
    ADD COLUMN default_intro_start_seconds BIGINT NULL AFTER production_studio,
    ADD COLUMN default_intro_end_seconds BIGINT NULL AFTER default_intro_start_seconds,
    ADD COLUMN default_outro_start_seconds BIGINT NULL AFTER default_intro_end_seconds,
    ADD CONSTRAINT chk_screen_titles_default_intro_interval CHECK (
        (default_intro_start_seconds IS NULL AND default_intro_end_seconds IS NULL)
        OR (
            default_intro_start_seconds IS NOT NULL
            AND default_intro_end_seconds IS NOT NULL
            AND default_intro_start_seconds >= 0
            AND default_intro_end_seconds > default_intro_start_seconds
        )
    ),
    ADD CONSTRAINT chk_screen_titles_default_outro_start CHECK (
        default_outro_start_seconds IS NULL OR default_outro_start_seconds >= 0
    ),
    ADD CONSTRAINT chk_screen_titles_default_timeline_order CHECK (
        default_intro_end_seconds IS NULL
        OR default_outro_start_seconds IS NULL
        OR default_outro_start_seconds >= default_intro_end_seconds
    );

ALTER TABLE screen_entries
    ADD COLUMN intro_mode VARCHAR(20) NOT NULL DEFAULT 'INHERIT' AFTER duration_seconds,
    ADD COLUMN intro_start_seconds BIGINT NULL AFTER intro_mode,
    ADD COLUMN intro_end_seconds BIGINT NULL AFTER intro_start_seconds,
    ADD COLUMN outro_mode VARCHAR(20) NOT NULL DEFAULT 'INHERIT' AFTER intro_end_seconds,
    ADD COLUMN outro_start_seconds BIGINT NULL AFTER outro_mode,
    ADD CONSTRAINT chk_screen_entries_intro_mode CHECK (
        intro_mode IN ('INHERIT', 'OVERRIDE', 'DISABLED')
    ),
    ADD CONSTRAINT chk_screen_entries_intro_interval CHECK (
        (
            intro_mode IN ('INHERIT', 'DISABLED')
            AND intro_start_seconds IS NULL
            AND intro_end_seconds IS NULL
        )
        OR (
            intro_mode = 'OVERRIDE'
            AND intro_start_seconds IS NOT NULL
            AND intro_end_seconds IS NOT NULL
            AND intro_start_seconds >= 0
            AND intro_end_seconds > intro_start_seconds
        )
    ),
    ADD CONSTRAINT chk_screen_entries_outro_mode CHECK (
        outro_mode IN ('INHERIT', 'OVERRIDE', 'DISABLED')
    ),
    ADD CONSTRAINT chk_screen_entries_outro_start CHECK (
        (
            outro_mode IN ('INHERIT', 'DISABLED')
            AND outro_start_seconds IS NULL
        )
        OR (
            outro_mode = 'OVERRIDE'
            AND outro_start_seconds IS NOT NULL
            AND outro_start_seconds >= 0
        )
    ),
    ADD CONSTRAINT chk_screen_entries_override_timeline_order CHECK (
        intro_mode <> 'OVERRIDE'
        OR outro_mode <> 'OVERRIDE'
        OR outro_start_seconds >= intro_end_seconds
    );
