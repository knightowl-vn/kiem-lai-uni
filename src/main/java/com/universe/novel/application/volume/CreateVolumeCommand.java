package com.universe.novel.application.volume;

import java.util.UUID;

public record CreateVolumeCommand(
        String title,
        String description,
        int sortOrder,
        UUID actorId
) {
}
