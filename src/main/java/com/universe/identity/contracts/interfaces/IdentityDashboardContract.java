package com.universe.identity.contracts.interfaces;

import com.universe.identity.contracts.admin.dto.IdentityDashboardSnapshot;

import java.time.Instant;

public interface IdentityDashboardContract {

    IdentityDashboardSnapshot getSnapshot(
            Instant createdSince
    );
}