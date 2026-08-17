package com.universe.novel.application.volume;

import java.util.UUID;

public record PublishVolumeCommand(
        UUID volumeId,
        UUID actorId
) {
}