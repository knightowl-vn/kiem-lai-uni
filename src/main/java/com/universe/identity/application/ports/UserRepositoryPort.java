package com.universe.identity.application.ports;

import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepositoryPort {

    Optional<User> findById(UUID userId);

    Optional<User> findByEmail(Email email);

    Optional<User> findByProviderSubject(
            AuthProvider authProvider,
            String providerSubject
    );

    boolean existsByEmail(Email email);

    void save(User user);
}