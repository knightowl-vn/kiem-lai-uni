package com.universe.screen.application.commands;

import com.universe.screen.domain.ScreenEntryType;

import java.util.UUID;

public record UpdateScreenEntryCommand(
        UUID titleId,
        UUID entryId,
        String slug,
        ScreenEntryType type,
        Integer number,
        String title,
        String synopsis,
        Long durationSeconds
) {
}
