package com.universe.identity.application.ports;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;

import java.util.Optional;

public interface AuthenticatedRequestIdentityQueryPort {

    Optional<AuthenticatedRequestIdentity> findByEmail(String email);
}
