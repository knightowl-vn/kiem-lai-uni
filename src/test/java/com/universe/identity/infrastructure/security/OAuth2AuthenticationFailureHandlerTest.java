package com.universe.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2AuthenticationFailureHandlerTest {

    private OAuth2ReturnToStore returnToStore;
    private SafeReturnToValidator returnToValidator;
    private OAuth2AuthenticationFailureHandler failureHandler;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockHttpSession session;
    private AuthenticationException exception;

    @BeforeEach
    void setUp() {
        returnToStore = new OAuth2ReturnToStore();
        returnToValidator = new SafeReturnToValidator();
        failureHandler = new OAuth2AuthenticationFailureHandler(returnToStore, returnToValidator);

        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        response = new MockHttpServletResponse();
        exception = new BadCredentialsException("OAuth authentication failed");
    }

    @Test
    @DisplayName("failure with valid returnTo redirects to /login?oauthError with encoded returnTo")
    void failureWithValidReturnToPreservesTarget() throws Exception {
        request.setParameter("state", "fail-state-1");
        returnToStore.store(session, "fail-state-1", "/novel/chapters/quyen-1-chuong-1?discussionBlock=b2");

        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl())
                .isEqualTo("/login?oauthError&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-1%3FdiscussionBlock%3Db2");
        // Verify one-time consume
        assertThat(returnToStore.consume(session, "fail-state-1")).isEmpty();
    }

    @Test
    @DisplayName("failure without returnTo or unknown state redirects to /login?oauthError without returnTo")
    void failureWithoutReturnToRedirectsToGenericOAuthError() throws Exception {
        request.setParameter("state", "fail-state-empty");

        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauthError");
    }

    @Test
    @DisplayName("failure with null state parameter redirects to /login?oauthError")
    void failureWithNullStateRedirectsToGenericOAuthError() throws Exception {
        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauthError");
    }

    @Test
    @DisplayName("failure with invalid or tampered returnTo in store falls back to /login?oauthError")
    void failureWithInvalidReturnToFallsBackToGenericError() throws Exception {
        request.setParameter("state", "fail-state-tampered");
        returnToStore.store(session, "fail-state-tampered", "https://evil.example");

        failureHandler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?oauthError");
    }
}
