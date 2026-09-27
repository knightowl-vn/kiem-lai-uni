package com.universe.identity.infrastructure.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates whether a candidate returnTo string is a safe and allowed internal redirect target.
 * Permits any internal user-facing path (including /, /home, /novel/**, /wiki/**, /donghua/**, etc.)
 * while strictly rejecting external origins, schemes, control characters, backslashes, directory
 * traversal escapes, and operational, administrative, authentication, and static asset routes.
 */
public class SafeReturnToValidator {

    private static final int MAX_LENGTH = 2048;

    private static final List<String> DENIED_PREFIXES = List.of(
            "/api",
            "/admin",
            "/login",
            "/register",
            "/logout",
            "/oauth2",
            "/.well-known",
            "/error",
            "/access-denied",
            "/css",
            "/js",
            "/images",
            "/favicon.ico"
    );

    /**
     * Validates whether a candidate returnTo string is a safe and allowed internal redirect target.
     *
     * @param returnTo the candidate redirect target string
     * @return true if valid and safe, false otherwise
     */
    public boolean isValid(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }

        if (returnTo.length() > MAX_LENGTH) {
            return false;
        }

        // Must not contain control characters or newlines (prevents HTTP response splitting / header injection)
        for (int i = 0; i < returnTo.length(); i++) {
            char c = returnTo.charAt(i);
            if (c < 32 || c == 127) {
                return false;
            }
        }

        // Must not contain raw backslash (prevents backslash authority bypasses like /\evil.com or \\evil.com)
        if (returnTo.indexOf('\\') >= 0) {
            return false;
        }

        // Must start with a single slash and not be scheme-relative (//evil.example)
        if (!returnTo.startsWith("/") || returnTo.startsWith("//")) {
            return false;
        }

        // Must parse as a valid relative URI with no host, authority, or scheme
        URI uri;
        try {
            uri = URI.create(returnTo);
            if (uri.isAbsolute() || uri.getScheme() != null || uri.getHost() != null || uri.getAuthority() != null) {
                return false;
            }
        } catch (Exception ex) {
            return false;
        }

        // Path component must be non-empty and start with /
        String decodedPath = uri.getPath();
        if (decodedPath == null || decodedPath.isBlank() || !decodedPath.startsWith("/")) {
            return false;
        }

        // Decoded path must not start with // or become authority-like
        if (decodedPath.startsWith("//")) {
            return false;
        }

        // Decoded path must not contain backslash (e.g. /%5Cevil or /%5cevil)
        if (decodedPath.indexOf('\\') >= 0) {
            return false;
        }

        // Decoded path must not contain control characters
        for (int i = 0; i < decodedPath.length(); i++) {
            char c = decodedPath.charAt(i);
            if (c < 32 || c == 127) {
                return false;
            }
        }

        // Decoded path must not contain colon (prevents scheme bypasses or drive letters)
        if (decodedPath.indexOf(':') >= 0) {
            return false;
        }

        // Reject multi-layer / double-encoded separators or traversal octets
        String lowerDecoded = decodedPath.toLowerCase();
        if (lowerDecoded.contains("%2e") || lowerDecoded.contains("%2f") || lowerDecoded.contains("%5c")) {
            return false;
        }

        // Normalize raw URI path to catch standard dot segments (e.g. /novel/../admin)
        String normalizedUriPath;
        try {
            normalizedUriPath = uri.normalize().getPath();
        } catch (Exception ex) {
            return false;
        }

        if (normalizedUriPath == null || !normalizedUriPath.startsWith("/") || normalizedUriPath.startsWith("/..")) {
            return false;
        }

        if (isDeniedPath(normalizedUriPath)) {
            return false;
        }

        // Canonicalize decoded path segments to resolve encoded traversal (e.g. /novel/%2e%2e/admin -> /admin)
        String canonicalPath = canonicalizePath(decodedPath);
        if (canonicalPath == null || !canonicalPath.startsWith("/") || canonicalPath.startsWith("//")) {
            return false;
        }

        if (isDeniedPath(canonicalPath)) {
            return false;
        }

        return true;
    }

    private String canonicalizePath(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }

        String[] segments = path.split("/");
        List<String> canonicalSegments = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (canonicalSegments.isEmpty()) {
                    return null; // Traversal escapes above root
                }
                canonicalSegments.remove(canonicalSegments.size() - 1);
            } else {
                canonicalSegments.add(segment);
            }
        }

        if (canonicalSegments.isEmpty()) {
            return "/";
        }

        StringBuilder sb = new StringBuilder();
        for (String seg : canonicalSegments) {
            sb.append("/").append(seg);
        }
        return sb.toString();
    }

    private boolean isDeniedPath(String path) {
        for (String denied : DENIED_PREFIXES) {
            if (path.equals(denied) || path.startsWith(denied + "/")) {
                return true;
            }
        }
        return false;
    }
}
