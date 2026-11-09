package com.universe.screen.application.commands;

import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenTimelineMode;

import java.util.UUID;

public record UpdateScreenEntryCommand(
        UUID titleId,
        UUID entryId,
        String slug,
        ScreenEntryType type,
        Integer number,
        String title,
        String synopsis,
        Long durationSeconds,
        boolean updateTimeline,
        ScreenTimelineMode introMode,
        Long introStartSeconds,
        Long introEndSeconds,
        ScreenTimelineMode outroMode,
        Long outroStartSeconds
) {

    /**
     * Explicit entry details and timeline update constructor.
     */
    public UpdateScreenEntryCommand(
            UUID titleId,
            UUID entryId,
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            Long durationSeconds,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds
    ) {
        this(
                titleId,
                entryId,
                slug,
                type,
                number,
                title,
                synopsis,
                durationSeconds,
                true,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds
        );
    }

    /**
     * Legacy constructor where timeline configuration is omitted.
     * Preserves existing timeline mode and override values on the entry.
     */
    public UpdateScreenEntryCommand(
            UUID titleId,
            UUID entryId,
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            Long durationSeconds
    ) {
        this(
                titleId,
                entryId,
                slug,
                type,
                number,
                title,
                synopsis,
                durationSeconds,
                false,
                null,
                null,
                null,
                null,
                null
        );
    }
}
