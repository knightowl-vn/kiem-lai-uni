package com.universe.identity.infrastructure.security;

import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.savedrequest.RequestCache;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleOAuthSuccessHandlerTest {

    private GoogleOAuthUserService userService;
    private OAuth2ReturnToStore returnToStore;
    private SafeReturnToValidator returnToValidator;
    private RequestCache requestCache;
    private GoogleOAuthSuccessHandler handler;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockHttpSession session;
    private User mockUser;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(GoogleOAuthUserService.class);
        returnToStore = new OAuth2ReturnToStore();
        returnToValidator = new SafeReturnToValidator();
        requestCache = Mockito.mock(RequestCache.class);
        handler = new GoogleOAuthSuccessHandler(userService, returnToStore, returnToValidator, requestCache);

        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        response = new MockHttpServletResponse();

        mockUser = Mockito.mock(User.class);
        when(mockUser.getId()).thenReturn(UUID.randomUUID());
        when(mockUser.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(mockUser.getRole()).thenReturn(UserRole.USER);
        when(userService.findOrCreateGoogleUser(any())).thenReturn(mockUser);
    }

    private OAuth2AuthenticationToken createOAuthToken() {
        OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "sub", "google-sub-12345",
                        "email", "reader@example.com",
                        "name", "Test Reader",
                        "picture", "https://avatar.example/pic.png",
                        "email_verified", true
                ),
                "sub"
        );
        return new OAuth2AuthenticationToken(oauth2User, List.of(new SimpleGrantedAuthority("ROLE_USER")), "google");
    }

    @Test
    @DisplayName("active user with valid returnTo in store redirects to returnTo and clears requestCache")
    void activeUserWithReturnToRedirectsToTarget() throws Exception {
        request.setParameter("state", "oauth-state-1");
        returnToStore.store(session, "oauth-state-1", "/novel/chapters/quyen-1-chuong-1?discussionBlock=b2");

        handler.onAuthenticationSuccess(request, response, createOAuthToken());

        verify(requestCache).removeRequest(request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/novel/chapters/quyen-1-chuong-1?discussionBlock=b2");
        // Ensure one-time consume:
        assertThat(returnToStore.consume(session, "oauth-state-1")).isEmpty();
    }

    @Test
    @DisplayName("active user without returnTo redirects to default /home and does not clear requestCache")
    void activeUserWithoutReturnToRedirectsToHome() throws Exception {
        request.setParameter("state", "oauth-state-empty");

        handler.onAuthenticationSuccess(request, response, createOAuthToken());

        verify(requestCache, never()).removeRequest(request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/home");
    }

    @Test
    @DisplayName("administrator without returnTo redirects to /admin/dashboard")
    void adminWithoutReturnToRedirectsToAdminDashboard() throws Exception {
        when(mockUser.getRole()).thenReturn(UserRole.ADMIN);
        request.setParameter("state", "oauth-state-admin");

        handler.onAuthenticationSuccess(request, response, createOAuthToken());

        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/dashboard");
    }

    @Test
    @DisplayName("active user with tampered invalid returnTo in store falls back to safe default /home")
    void activeUserWithInvalidReturnToFallsBackToDefault() throws Exception {
        request.setParameter("state", "oauth-state-tampered");
        returnToStore.store(session, "oauth-state-tampered", "https://evil.example");

        handler.onAuthenticationSuccess(request, response, createOAuthToken());

        verify(requestCache, never()).removeRequest(request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/home");
    }

    @Test
    @DisplayName("blocked user with valid returnTo redirects to /login?blocked with encoded returnTo")
    void blockedUserPreservesReturnTo() throws Exception {
        when(mockUser.getStatus()).thenReturn(UserStatus.BLOCKED);
        request.setParameter("state", "oauth-state-blocked");
        returnToStore.store(session, "oauth-state-blocked", "/novel/chapters/quyen-1-chuong-1");

        handler.onAuthenticationSuccess(request, response, createOAuthToken());

        assertThat(response.getRedirectedUrl())
                .isEqualTo("/login?blocked&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-1");
    }

    @Test
    @DisplayName("disabled user with valid returnTo redirects to /login?disabled with encoded returnTo")
    void disabledUserPreservesReturnTo() throws Exception {
        when(mockUser.getStatus()).thenReturn(UserStatus.UNVERIFIED);
        request.setParameter("state", "oauth-state-disabled");
        returnToStore.store(session, "oauth-state-disabled", "/wiki/character/tran-binh-an");

        handler.onAuthenticationSuccess(request, response, createOAuthToken());

        assertThat(response.getRedirectedUrl())
                .isEqualTo("/login?disabled&returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an");
    }
}
