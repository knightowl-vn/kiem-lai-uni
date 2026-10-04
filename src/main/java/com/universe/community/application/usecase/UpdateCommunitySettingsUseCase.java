package com.universe.community.application.usecase;

import com.universe.community.application.command.UpdateCommunitySettingsCommand;
import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
import com.universe.community.domain.CommunitySettings;
import com.universe.community.domain.exception.CommunitySettingsOptimisticLockException;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Use case to update Community runtime settings with optimistic lock verification.
 */
@Service
public class UpdateCommunitySettingsUseCase {

    private final CommunitySettingsRepositoryPort settingsRepositoryPort;
    private final ClockPort clockPort;

    public UpdateCommunitySettingsUseCase(
            CommunitySettingsRepositoryPort settingsRepositoryPort,
            ClockPort clockPort
    ) {
        this.settingsRepositoryPort = Objects.requireNonNull(settingsRepositoryPort, "CommunitySettingsRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public CommunitySettings execute(UpdateCommunitySettingsCommand command) {
        Objects.requireNonNull(command, "UpdateCommunitySettingsCommand cannot be null.");

        Instant now = clockPort.now();
        boolean updated = settingsRepositoryPort.updateSettings(
                command.publicationMode(),
                command.expectedVersion(),
                command.updatedByUserId(),
                now
        );

        if (!updated) {
            throw new CommunitySettingsOptimisticLockException(command.expectedVersion());
        }

        return settingsRepositoryPort.getSettings();
    }
}
