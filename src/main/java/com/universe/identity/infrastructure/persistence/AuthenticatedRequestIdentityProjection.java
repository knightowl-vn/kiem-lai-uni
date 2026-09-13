package com.universe.identity.infrastructure.persistence;

import com.universe.identity.domain.UserRole;

public interface AuthenticatedRequestIdentityProjection {

    String getUserId();

    String getNormalizedEmail();

    String getDisplayName();

    String getAvatarUrl();

    String getStatus();

    UserRole getRole();
}
