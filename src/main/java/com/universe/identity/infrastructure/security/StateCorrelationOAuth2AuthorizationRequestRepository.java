package com.universe.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-tab safe, session-backed AuthorizationRequestRepository that persists
 * pending OAuth2AuthorizationRequest objects keyed by their exact OAuth state,
 * while simultaneously correlating and preserving validated returnTo destination targets.
 *
 * It avoids the single-slot overwrite limitation of Spring's default
 * HttpSessionOAuth2AuthorizationRequestRepository by maintaining a bounded
 * insertion-order map of up to 10 pending requests per session.
 *
 * Crucially, when removeAuthorizationRequest is called, it removes the authorization
 * request from the session while leaving the state-to-returnTo mapping in OAuth2ReturnToStore
 * intact so that subsequent authentication success or failure handlers can consume it.
 */
public class StateCorrelationOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_AUTHORIZATION_REQUEST_ATTR_NAME =
            "KIEMLAI_OAUTH2_AUTHORIZATION_REQUEST_STORE";
    private static final int MAX_ENTRIES = 10;

    private final String sessionAttributeName;
    private final OAuth2ReturnToStore returnToStore;
    private final SafeReturnToValidator returnToValidator;

    public StateCorrelationOAuth2AuthorizationRequestRepository(
            OAuth2ReturnToStore returnToStore,
            SafeReturnToValidator returnToValidator
    ) {
        this(DEFAULT_AUTHORIZATION_REQUEST_ATTR_NAME, returnToStore, returnToValidator);
    }

    public StateCorrelationOAuth2AuthorizationRequestRepository(
            String sessionAttributeName,
            OAuth2ReturnToStore returnToStore,
            SafeReturnToValidator returnToValidator
    ) {
        this.sessionAttributeName = Objects.requireNonNull(sessionAttributeName, "sessionAttributeName cannot be null");
        this.returnToStore = Objects.requireNonNull(returnToStore, "OAuth2ReturnToStore cannot be null");
        this.returnToValidator = Objects.requireNonNull(returnToValidator, "SafeReturnToValidator cannot be null");
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String state = request.getParameter("state");
        if (state == null || state.isBlank()) {
            return null;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }

        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, OAuth2AuthorizationRequest> store =
                    (Map<String, OAuth2AuthorizationRequest>) session.getAttribute(sessionAttributeName);
            if (store == null) {
                return null;
            }
            return store.get(state);
        }
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (request == null) {
            return;
        }

        if (authorizationRequest == null) {
            String state = request.getParameter("state");
            if (state == null || state.isBlank()) {
                return;
            }
            HttpSession session = request.getSession(false);
            if (session == null) {
                return;
            }
            synchronized (session) {
                @SuppressWarnings("unchecked")
                Map<String, OAuth2AuthorizationRequest> store =
                        (Map<String, OAuth2AuthorizationRequest>) session.getAttribute(sessionAttributeName);
                if (store != null) {
                    store.remove(state);
                    if (store.isEmpty()) {
                        session.removeAttribute(sessionAttributeName);
                    } else {
                        session.setAttribute(sessionAttributeName, store);
                    }
                }
            }
            return;
        }

        String state = authorizationRequest.getState();
        if (state == null || state.isBlank()) {
            return;
        }

        HttpSession session = request.getSession(true);
        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, OAuth2AuthorizationRequest> store =
                    (Map<String, OAuth2AuthorizationRequest>) session.getAttribute(sessionAttributeName);
            if (store == null) {
                store = new BoundedAuthorizationRequestMap(MAX_ENTRIES);
            }
            store.put(state, authorizationRequest);
            session.setAttribute(sessionAttributeName, store);
        }

        String returnTo = request.getParameter("returnTo");
        if (returnTo != null && returnToValidator.isValid(returnTo)) {
            returnToStore.store(request, state, returnTo);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (request == null) {
            return null;
        }

        String state = request.getParameter("state");
        if (state == null || state.isBlank()) {
            return null;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }

        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, OAuth2AuthorizationRequest> store =
                    (Map<String, OAuth2AuthorizationRequest>) session.getAttribute(sessionAttributeName);
            if (store == null) {
                return null;
            }
            OAuth2AuthorizationRequest removed = store.remove(state);
            if (store.isEmpty()) {
                session.removeAttribute(sessionAttributeName);
            } else {
                session.setAttribute(sessionAttributeName, store);
            }
            return removed;
        }
    }

    private static class BoundedAuthorizationRequestMap extends LinkedHashMap<String, OAuth2AuthorizationRequest> {
        private static final long serialVersionUID = 1L;
        private final int maxEntries;

        BoundedAuthorizationRequestMap(int maxEntries) {
            super(maxEntries, 0.75f, false);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OAuth2AuthorizationRequest> eldest) {
            return size() > maxEntries;
        }
    }
}
