package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
import com.universe.community.domain.CommunitySettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Use case to retrieve the current authoritative Community runtime settings.
 */
@Service
@Transactional(readOnly = true)
public class GetCommunitySettingsUseCase {

    private final CommunitySettingsRepositoryPort settingsRepositoryPort;

    public GetCommunitySettingsUseCase(CommunitySettingsRepositoryPort settingsRepositoryPort) {
        this.settingsRepositoryPort = Objects.requireNonNull(settingsRepositoryPort, "CommunitySettingsRepositoryPort cannot be null.");
    }

    public CommunitySettings execute() {
        return settingsRepositoryPort.getSettings();
    }
}
