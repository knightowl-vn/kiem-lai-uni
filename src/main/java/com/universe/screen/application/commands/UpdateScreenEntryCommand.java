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
        Long outroStartSeconds,
        boolean updateOutroEnd,
        Long outroEndSeconds
) {

    /**
     * Explicit entry details and timeline update constructor including outro end boundary.
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
            Long outroStartSeconds,
            Long outroEndSeconds
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
                outroStartSeconds,
                true,
                outroEndSeconds
        );
    }

    /**
     * Explicit entry details and timeline update constructor with explicit updateTimeline flag
     * including outro end boundary.
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
            boolean updateTimeline,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds
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
                updateTimeline,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                true,
                outroEndSeconds
        );
    }

    /**
     * Explicit entry details and timeline update constructor (without outro end boundary).
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
                outroStartSeconds,
                false,
                null
        );
    }

    /**
     * Backward-compatible 14-parameter constructor with explicit updateTimeline flag
     * (without outro end boundary).
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
            boolean updateTimeline,
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
                updateTimeline,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                false,
                null
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
                null,
                false,
                null
        );
    }
}
