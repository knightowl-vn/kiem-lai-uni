package com.universe.screen.application.commands;

import com.universe.screen.domain.ScreenEntryType;

import java.util.UUID;

public record CreateScreenEntryCommand(
        UUID titleId,
        String slug,
        ScreenEntryType type,
        Integer number,
        String title,
        String synopsis,
        Long durationSeconds
) {
}
