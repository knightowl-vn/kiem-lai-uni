package com.universe.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches only legitimate browser navigation GET requests suitable for authentication SavedRequest caching.
 * Excludes background probes (such as /.well-known/**), REST APIs, static assets, favicon probes, and non-GET requests.
 */
public class BrowserNavigationRequestMatcher implements RequestMatcher {

    private static final java.util.List<String> EXCLUDED_SEGMENT_ROOTS = java.util.List.of(
            "/.well-known",
            "/api",
            "/css",
            "/js",
            "/images"
    );

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }

        // Exclude background probes, APIs, static assets, error dispatches, login/register pages
        if (isExcludedPath(uri)) {
            return false;
        }

        // Exclude AJAX / XHR requests
        if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
            return false;
        }

        // Inspect Accept header: legitimate browser navigation accepts text/html or general media
        String accept = request.getHeader("Accept");
        if (accept != null) {
            if (!accept.contains("text/html") && !accept.contains("*/*")) {
                return false;
            }
        }

        return true;
    }

    private static boolean isExcludedPath(String uri) {
        for (String root : EXCLUDED_SEGMENT_ROOTS) {
            if (uri.equals(root) || uri.startsWith(root + "/")) {
                return true;
            }
        }
        return uri.equals("/favicon.ico")
                || uri.equals("/error")
                || uri.equals("/login")
                || uri.equals("/register");
    }
}
