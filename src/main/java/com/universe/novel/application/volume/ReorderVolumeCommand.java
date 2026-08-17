package com.universe.novel.application.volume;

import java.util.UUID;

public record ReorderVolumeCommand(
        UUID volumeId,
        int sortOrder,
        UUID actorId
) {
}