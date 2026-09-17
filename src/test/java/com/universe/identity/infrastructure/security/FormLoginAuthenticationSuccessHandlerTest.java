package com.universe.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.RequestCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FormLoginAuthenticationSuccessHandlerTest {

    private RequestCache requestCache;
    private SafeReturnToValidator returnToValidator;
    private FormLoginAuthenticationSuccessHandler handler;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        requestCache = mock(RequestCache.class);
        returnToValidator = new SafeReturnToValidator();
        handler = new FormLoginAuthenticationSuccessHandler(requestCache, returnToValidator);
        authentication = new TestingAuthenticationToken("user@example.com", "password", "ROLE_USER");
    }

    @Test
    @DisplayName("valid returnTo redirects exactly to internal chapter target")
    void validReturnToRedirectsDirectly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/novel/chapters/quyen-1-chuong-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/novel/chapters/quyen-1-chuong-1");
    }

    @Test
    @DisplayName("valid returnTo redirects exactly to internal wiki target")
    void validReturnToRedirectsDirectlyToWiki() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/wiki/character/tran-binh-an");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/wiki/character/tran-binh-an");
    }

    @Test
    @DisplayName("explicit valid returnTo takes precedence over SavedRequest and removes stale SavedRequest")
    void validReturnToPrecedesAndCleansSavedRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "/novel/chapters/quyen-1-chuong-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        DefaultSavedRequest staleSavedRequest = mock(DefaultSavedRequest.class);
        when(staleSavedRequest.getRedirectUrl()).thenReturn("/admin/novel/volumes");
        when(requestCache.getRequest(any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn(staleSavedRequest);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/novel/chapters/quyen-1-chuong-1");
        assertThat(response.getRedirectedUrl()).isNotEqualTo("/admin/novel/volumes");
        verify(requestCache).removeRequest(request, response);
        verify(staleSavedRequest, never()).getRedirectUrl();
    }

    @Test
    @DisplayName("no returnTo delegates to legitimate SavedRequest")
    void noReturnToDelegatesToSavedRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        DefaultSavedRequest savedRequest = mock(DefaultSavedRequest.class);
        when(savedRequest.getRedirectUrl()).thenReturn("/admin/novel/volumes");
        when(requestCache.getRequest(any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn(savedRequest);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/novel/volumes");
    }

    @Test
    @DisplayName("no returnTo and no SavedRequest falls back to /home")
    void noReturnToAndNoSavedRequestFallsBackToHome() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(requestCache.getRequest(any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/home");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example",
            "http://evil.example",
            "//evil.example",
            "/\\evil.example",
            "\\\\evil.example",
            "javascript:alert(1)",
            "data:text/html,evil",
            "/novel/chapters/1\r\nInjected:header",
            ""
    })
    @DisplayName("invalid or malicious returnTo does NOT redirect externally and falls back safely to /home")
    void invalidReturnToFallsBackSafelyToHome(String invalidTarget) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", invalidTarget);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(requestCache.getRequest(any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn(null);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/home");
        assertThat(response.getRedirectedUrl()).doesNotContain("evil");
    }

    @Test
    @DisplayName("invalid returnTo falls back to SavedRequest if present")
    void invalidReturnToFallsBackToSavedRequestIfPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("returnTo", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        DefaultSavedRequest savedRequest = mock(DefaultSavedRequest.class);
        when(savedRequest.getRedirectUrl()).thenReturn("/novel/bookmarks");
        when(requestCache.getRequest(any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn(savedRequest);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/novel/bookmarks");
    }
}
