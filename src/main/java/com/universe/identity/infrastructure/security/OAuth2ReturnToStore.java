package com.universe.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Session-backed, bounded, multi-tab safe store that maps OAuth state correlation
 * keys to validated returnTo destination targets.
 *
 * Entries are bounded to a maximum capacity (10 per session) using bounded insertion-order / oldest-entry eviction,
 * and are consumed exactly once upon OAuth completion or failure.
 */
public class OAuth2ReturnToStore implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String SESSION_ATTRIBUTE_NAME = "KIEMLAI_OAUTH2_RETURN_TO_STORE";
    private static final int MAX_ENTRIES = 10;

    /**
     * Associates a returnTo target with the given OAuth state in the current session.
     *
     * @param request the current HTTP servlet request
     * @param state the OAuth state correlation key
     * @param returnTo the validated returnTo path
     */
    public void store(HttpServletRequest request, String state, String returnTo) {
        if (request == null || state == null || state.isBlank() || returnTo == null || returnTo.isBlank()) {
            return;
        }
        HttpSession session = request.getSession(true);
        store(session, state, returnTo);
    }

    /**
     * Associates a returnTo target with the given OAuth state in the provided session.
     *
     * @param session the HTTP session
     * @param state the OAuth state correlation key
     * @param returnTo the validated returnTo path
     */
    public void store(HttpSession session, String state, String returnTo) {
        if (session == null || state == null || state.isBlank() || returnTo == null || returnTo.isBlank()) {
            return;
        }
        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, String> store = (Map<String, String>) session.getAttribute(SESSION_ATTRIBUTE_NAME);
            if (store == null) {
                store = new BoundedStateMap(MAX_ENTRIES);
            }
            store.put(state, returnTo);
            session.setAttribute(SESSION_ATTRIBUTE_NAME, store);
        }
    }

    /**
     * Consumes and removes the returnTo target associated with the given OAuth state.
     *
     * @param request the current HTTP servlet request
     * @param state the OAuth state correlation key
     * @return the associated returnTo, or empty if absent or not found
     */
    public Optional<String> consume(HttpServletRequest request, String state) {
        if (request == null || state == null || state.isBlank()) {
            return Optional.empty();
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return consume(session, state);
    }

    /**
     * Consumes and removes the returnTo target associated with the given OAuth state.
     *
     * @param session the HTTP session
     * @param state the OAuth state correlation key
     * @return the associated returnTo, or empty if absent or not found
     */
    public Optional<String> consume(HttpSession session, String state) {
        if (session == null || state == null || state.isBlank()) {
            return Optional.empty();
        }
        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, String> store = (Map<String, String>) session.getAttribute(SESSION_ATTRIBUTE_NAME);
            if (store == null) {
                return Optional.empty();
            }
            String returnTo = store.remove(state);
            if (store.isEmpty()) {
                session.removeAttribute(SESSION_ATTRIBUTE_NAME);
            } else {
                session.setAttribute(SESSION_ATTRIBUTE_NAME, store);
            }
            return Optional.ofNullable(returnTo);
        }
    }

    /**
     * Reads without removing the returnTo target associated with the given OAuth state (useful for inspection/testing).
     *
     * @param session the HTTP session
     * @param state the OAuth state correlation key
     * @return the associated returnTo, or empty if absent
     */
    public Optional<String> peek(HttpSession session, String state) {
        if (session == null || state == null || state.isBlank()) {
            return Optional.empty();
        }
        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, String> store = (Map<String, String>) session.getAttribute(SESSION_ATTRIBUTE_NAME);
            if (store == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(store.get(state));
        }
    }

    private static class BoundedStateMap extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;
        private final int maxEntries;

        BoundedStateMap(int maxEntries) {
            super(maxEntries, 0.75f, false);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > maxEntries;
        }
    }
}
