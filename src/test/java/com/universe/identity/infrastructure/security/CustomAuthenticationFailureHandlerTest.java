package com.universe.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

import static org.assertj.core.api.Assertions.assertThat;

class CustomAuthenticationFailureHandlerTest {

    private SafeReturnToValidator returnToValidator;
    private CustomAuthenticationFailureHandler failureHandler;

    @BeforeEach
    void setUp() {
        returnToValidator = new SafeReturnToValidator();
        failureHandler = new CustomAuthenticationFailureHandler(returnToValidator);
    }

    @Test
    @DisplayName("valid returnTo is preserved on bad credentials failure")
    void validReturnToIsPreservedOnBadCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/novel/chapters/quyen-1-chuong-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-1");
    }

    @Test
    @DisplayName("valid wiki returnTo is preserved on bad credentials failure")
    void validWikiReturnToIsPreservedOnBadCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/wiki/character/tran-binh-an");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error&returnTo=%2Fwiki%2Fcharacter%2Ftran-binh-an");
    }

    @Test
    @DisplayName("valid returnTo is preserved on account locked failure")
    void validReturnToIsPreservedOnLockedAccount() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/novel/chapters/quyen-1-chuong-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response, new LockedException("Locked"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?blocked&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-1");
    }

    @Test
    @DisplayName("valid returnTo is preserved on account disabled failure")
    void validReturnToIsPreservedOnDisabledAccount() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/novel/chapters/quyen-1-chuong-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response, new DisabledException("Disabled"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?disabled&returnTo=%2Fnovel%2Fchapters%2Fquyen-1-chuong-1");
    }

    @Test
    @DisplayName("invalid returnTo is dropped on authentication failure")
    void invalidReturnToIsDroppedOnFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    @DisplayName("no returnTo parameter redirects to /login?error without query param")
    void noReturnToRedirectsNormally() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(request, response, new BadCredentialsException("Bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }
}
