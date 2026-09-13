package com.universe.identity.entry.web;

import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfilePageControllerTest {

    @Mock
    private CurrentUserQueryPort currentUserQueryPort;

    @Mock
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @Mock
    private Authentication authentication;

    private ProfilePageController controller;

    @BeforeEach
    void setUp() {
        controller = new ProfilePageController(
                currentUserQueryPort,
                authenticatedEmailResolver
        );
    }

    @Test
    @DisplayName("GET /profile renders identity/profile with user and hasPassword model attributes")
    void shouldRenderProfilePageSuccessfully() {
        String email = "user@example.com";
        when(authenticatedEmailResolver.require(authentication)).thenReturn(email);

        CurrentUserView userView = new CurrentUserView(
                "11111111-1111-1111-1111-111111111111",
                email,
                "Test User",
                "/media/assets/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/content",
                "Tiểu sử người dùng",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.parse("2026-08-20T10:00:00Z"),
                true
        );

        when(currentUserQueryPort.findByEmail(email)).thenReturn(Optional.of(userView));

        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.profilePage(authentication, model);

        assertThat(view).isEqualTo("identity/profile");
        assertThat(model.getAttribute("user")).isEqualTo(userView);
        assertThat(model.getAttribute("hasPassword")).isEqualTo(userView.hasLocalPassword());

        verify(authenticatedEmailResolver).require(authentication);
        verify(currentUserQueryPort).findByEmail(email);
    }
}
