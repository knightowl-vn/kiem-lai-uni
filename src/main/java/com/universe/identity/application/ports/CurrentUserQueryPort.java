package com.universe.identity.application.ports;

import com.universe.identity.contracts.currentuser.CurrentUserView;

import java.util.Optional;

public interface CurrentUserQueryPort {

    Optional<CurrentUserView> findByEmail(
            String email
    );
}