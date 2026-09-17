package com.universe.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserNavigationRequestMatcherTest {

    private BrowserNavigationRequestMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new BrowserNavigationRequestMatcher();
    }

    @Test
    @DisplayName("legitimate protected HTML navigation GET matches")
    void legitimateProtectedHtmlNavigationMatches() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/novel/volumes");
        request.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        assertThat(matcher.matches(request)).isTrue();

        MockHttpServletRequest bookmarkReq = new MockHttpServletRequest("GET", "/novel/bookmarks");
        bookmarkReq.addHeader("Accept", "text/html,application/xhtml+xml");
        assertThat(matcher.matches(bookmarkReq)).isTrue();
    }

    @Test
    @DisplayName(".well-known probe and exact root do not match")
    void wellKnownProbeDoesNotMatch() {
        MockHttpServletRequest exact = new MockHttpServletRequest("GET", "/.well-known");
        assertThat(matcher.matches(exact)).isFalse();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.well-known/appspecific/com.chrome.devtools.json");
        request.addHeader("Accept", "application/json");
        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    @DisplayName("API requests and exact root do not match")
    void apiRequestsDoNotMatch() {
        MockHttpServletRequest exact = new MockHttpServletRequest("GET", "/api");
        assertThat(matcher.matches(exact)).isFalse();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/novel/chapters/ch-1/comments");
        request.addHeader("Accept", "application/json");
        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    @DisplayName("static assets, exact asset roots, and favicon do not match")
    void staticAssetsAndFaviconDoNotMatch() {
        MockHttpServletRequest cssRoot = new MockHttpServletRequest("GET", "/css");
        assertThat(matcher.matches(cssRoot)).isFalse();
        MockHttpServletRequest css = new MockHttpServletRequest("GET", "/css/style.css");
        assertThat(matcher.matches(css)).isFalse();

        MockHttpServletRequest jsRoot = new MockHttpServletRequest("GET", "/js");
        assertThat(matcher.matches(jsRoot)).isFalse();
        MockHttpServletRequest js = new MockHttpServletRequest("GET", "/js/app.js");
        assertThat(matcher.matches(js)).isFalse();

        MockHttpServletRequest imgRoot = new MockHttpServletRequest("GET", "/images");
        assertThat(matcher.matches(imgRoot)).isFalse();
        MockHttpServletRequest img = new MockHttpServletRequest("GET", "/images/logo.png");
        assertThat(matcher.matches(img)).isFalse();

        MockHttpServletRequest favicon = new MockHttpServletRequest("GET", "/favicon.ico");
        assertThat(matcher.matches(favicon)).isFalse();
    }

    @Test
    @DisplayName("non-GET requests do not match")
    void nonGetRequestsDoNotMatch() {
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/admin/novel/volumes");
        assertThat(matcher.matches(post)).isFalse();

        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/novel/bookmarks");
        assertThat(matcher.matches(put)).isFalse();

        MockHttpServletRequest delete = new MockHttpServletRequest("DELETE", "/novel/bookmarks/1");
        assertThat(matcher.matches(delete)).isFalse();
    }

    @Test
    @DisplayName("XHR requests do not match")
    void xhrRequestsDoNotMatch() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/novel/volumes");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        request.addHeader("Accept", "text/html");
        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    @DisplayName("login, register, and error pages do not match")
    void authAndErrorPagesDoNotMatch() {
        MockHttpServletRequest login = new MockHttpServletRequest("GET", "/login");
        assertThat(matcher.matches(login)).isFalse();

        MockHttpServletRequest register = new MockHttpServletRequest("GET", "/register");
        assertThat(matcher.matches(register)).isFalse();

        MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error");
        assertThat(matcher.matches(error)).isFalse();
    }
}
