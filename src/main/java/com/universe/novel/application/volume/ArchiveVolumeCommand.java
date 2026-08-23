package com.universe.novel.application.volume;

import java.util.UUID;

public record ArchiveVolumeCommand(
        UUID volumeId,
        UUID actorId
) {
}