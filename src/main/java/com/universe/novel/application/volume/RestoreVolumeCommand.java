package com.universe.novel.application.volume;

import java.util.UUID;

public record RestoreVolumeCommand(
        UUID volumeId,
        UUID actorId
) {
}
