package com.universe.novel.application.volume;

import java.util.UUID;

public record UpdateDraftVolumeCommand(
        UUID volumeId,
        String title,
        String slug,
        String description,
        UUID actorId
) {
}