package com.universe.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

class StateCorrelationOAuth2AuthorizationRequestRepositoryTest {

    private static final String SESSION_ATTR_NAME = "KIEMLAI_OAUTH2_AUTHORIZATION_REQUEST_STORE";

    private OAuth2ReturnToStore store;
    private SafeReturnToValidator validator;
    private StateCorrelationOAuth2AuthorizationRequestRepository repository;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        store = new OAuth2ReturnToStore();
        validator = new SafeReturnToValidator();
        repository = new StateCorrelationOAuth2AuthorizationRequestRepository(store, validator);

        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        response = new MockHttpServletResponse();
    }

    private OAuth2AuthorizationRequest createAuthRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .state(state)
                .build();
    }

    @Test
    @DisplayName("deterministic multi-tab test: reverse completion order preserves both authorization requests and returnTo targets")
    void deterministicRealMultiTabEndToEndReverseCompletion() {
        // Tab A initiates OAuth
        MockHttpServletRequest reqA = new MockHttpServletRequest();
        reqA.setSession(session);
        reqA.setParameter("returnTo", "/novel/chapters/a");
        OAuth2AuthorizationRequest authA = createAuthRequest("STATE_A");
        repository.saveAuthorizationRequest(authA, reqA, new MockHttpServletResponse());

        // Tab B initiates OAuth in the same session
        MockHttpServletRequest reqB = new MockHttpServletRequest();
        reqB.setSession(session);
        reqB.setParameter("returnTo", "/wiki/articles/b");
        OAuth2AuthorizationRequest authB = createAuthRequest("STATE_B");
        repository.saveAuthorizationRequest(authB, reqB, new MockHttpServletResponse());

        // Simulate callback for B first (reverse completion order)
        MockHttpServletRequest callbackB = new MockHttpServletRequest();
        callbackB.setSession(session);
        callbackB.setParameter("state", "STATE_B");
        MockHttpServletResponse resB = new MockHttpServletResponse();

        assertThat(repository.loadAuthorizationRequest(callbackB)).isSameAs(authB);
        assertThat(repository.removeAuthorizationRequest(callbackB, resB)).isSameAs(authB);

        // Crucial invariant: returnTo B must STILL exist in store until handler consumes it
        assertThat(store.peek(session, "STATE_B")).contains("/wiki/articles/b");

        // Simulate callback for A second
        MockHttpServletRequest callbackA = new MockHttpServletRequest();
        callbackA.setSession(session);
        callbackA.setParameter("state", "STATE_A");
        MockHttpServletResponse resA = new MockHttpServletResponse();

        assertThat(repository.loadAuthorizationRequest(callbackA)).isSameAs(authA);
        assertThat(repository.removeAuthorizationRequest(callbackA, resA)).isSameAs(authA);

        // Crucial invariant: returnTo A must STILL exist in store
        assertThat(store.peek(session, "STATE_A")).contains("/novel/chapters/a");

        // Handlers consume return targets
        assertThat(store.consume(session, "STATE_B")).contains("/wiki/articles/b");
        assertThat(store.consume(session, "STATE_A")).contains("/novel/chapters/a");

        // Replay attempts must be absent
        assertThat(repository.loadAuthorizationRequest(callbackB)).isNull();
        assertThat(repository.removeAuthorizationRequest(callbackB, resB)).isNull();
        assertThat(store.consume(session, "STATE_B")).isEmpty();

        assertThat(repository.loadAuthorizationRequest(callbackA)).isNull();
        assertThat(repository.removeAuthorizationRequest(callbackA, resA)).isNull();
        assertThat(store.consume(session, "STATE_A")).isEmpty();
    }

    @Test
    @DisplayName("saving authorization request with valid returnTo stores authorization request and returnTo mapping")
    void saveAuthorizationRequestStoresValidReturnTo() {
        OAuth2AuthorizationRequest authRequest = createAuthRequest("state-alpha");
        request.setParameter("returnTo", "/novel/chapters/quyen-1-chuong-1?discussionBlock=b2");

        repository.saveAuthorizationRequest(authRequest, request, response);

        request.setParameter("state", "state-alpha");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authRequest);
        assertThat(store.consume(session, "state-alpha"))
                .contains("/novel/chapters/quyen-1-chuong-1?discussionBlock=b2");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example",
            "//evil.example",
            "/admin/users",
            "/login",
            "/register",
            "/api/auth/register",
            "/novel/../admin"
    })
    @DisplayName("saving authorization request with invalid returnTo stores request but rejects return target")
    void saveAuthorizationRequestRejectsInvalidReturnTo(String invalidTarget) {
        OAuth2AuthorizationRequest authRequest = createAuthRequest("state-invalid");
        request.setParameter("returnTo", invalidTarget);

        repository.saveAuthorizationRequest(authRequest, request, response);

        request.setParameter("state", "state-invalid");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authRequest);
        assertThat(store.consume(session, "state-invalid")).isEmpty();
    }

    @Test
    @DisplayName("saving authorization request without returnTo stores request without return target")
    void saveAuthorizationRequestWithoutReturnToDoesNotStore() {
        OAuth2AuthorizationRequest authRequest = createAuthRequest("state-empty");

        repository.saveAuthorizationRequest(authRequest, request, response);

        request.setParameter("state", "state-empty");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authRequest);
        assertThat(store.consume(session, "state-empty")).isEmpty();
    }

    @Test
    @DisplayName("saveAuthorizationRequest with null authorizationRequest removes only specified state without wiping others or returnTo")
    void saveNullAuthorizationRequestRemovesOnlySpecifiedState() {
        OAuth2AuthorizationRequest authA = createAuthRequest("STATE_A");
        OAuth2AuthorizationRequest authB = createAuthRequest("STATE_B");

        MockHttpServletRequest reqA = new MockHttpServletRequest();
        reqA.setSession(session);
        reqA.setParameter("returnTo", "/novel/chapters/a");
        repository.saveAuthorizationRequest(authA, reqA, response);

        MockHttpServletRequest reqB = new MockHttpServletRequest();
        reqB.setSession(session);
        reqB.setParameter("returnTo", "/wiki/articles/b");
        repository.saveAuthorizationRequest(authB, reqB, response);

        // Cancel A via saveAuthorizationRequest(null)
        reqA.setParameter("state", "STATE_A");
        repository.saveAuthorizationRequest(null, reqA, response);

        // A is removed from authorization requests
        assertThat(repository.loadAuthorizationRequest(reqA)).isNull();

        // B is NOT removed
        reqB.setParameter("state", "STATE_B");
        assertThat(repository.loadAuthorizationRequest(reqB)).isSameAs(authB);

        // returnTo mappings are not wiped
        assertThat(store.peek(session, "STATE_A")).contains("/novel/chapters/a");
        assertThat(store.peek(session, "STATE_B")).contains("/wiki/articles/b");
    }

    @Test
    @DisplayName("loadAuthorizationRequest does not consume or remove the authorization request")
    void loadAuthorizationRequestDoesNotConsume() {
        OAuth2AuthorizationRequest authRequest = createAuthRequest("state-peek");
        repository.saveAuthorizationRequest(authRequest, request, response);

        request.setParameter("state", "state-peek");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authRequest);
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authRequest);
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authRequest);
    }

    @Test
    @DisplayName("removing one authorization request does not remove unrelated pending requests")
    void removingBDoesNotRemoveA() {
        OAuth2AuthorizationRequest authA = createAuthRequest("STATE_A");
        OAuth2AuthorizationRequest authB = createAuthRequest("STATE_B");

        repository.saveAuthorizationRequest(authA, request, response);
        repository.saveAuthorizationRequest(authB, request, response);

        request.setParameter("state", "STATE_B");
        assertThat(repository.removeAuthorizationRequest(request, response)).isSameAs(authB);

        request.setParameter("state", "STATE_A");
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authA);
        assertThat(repository.removeAuthorizationRequest(request, response)).isSameAs(authA);
    }

    @Test
    @DisplayName("bounded capacity of 10 authorization requests evicts the oldest entry on 11th insertion")
    void boundedCapacityEvictsOldestOnly() {
        for (int i = 0; i < 10; i++) {
            repository.saveAuthorizationRequest(createAuthRequest("state-" + i), request, response);
        }

        // 11th entry
        repository.saveAuthorizationRequest(createAuthRequest("state-10"), request, response);

        // Oldest (state-0) must have been evicted
        request.setParameter("state", "state-0");
        assertThat(repository.loadAuthorizationRequest(request)).isNull();

        // States 1 through 10 remain intact
        for (int i = 1; i <= 10; i++) {
            request.setParameter("state", "state-" + i);
            assertThat(repository.loadAuthorizationRequest(request)).isNotNull();
        }
    }

    @Test
    @DisplayName("removing the last authorization request removes the session attribute")
    void emptyMapRemovesSessionAttribute() {
        OAuth2AuthorizationRequest auth = createAuthRequest("state-single");
        repository.saveAuthorizationRequest(auth, request, response);

        assertThat(session.getAttribute(SESSION_ATTR_NAME)).isNotNull();

        request.setParameter("state", "state-single");
        repository.removeAuthorizationRequest(request, response);

        assertThat(session.getAttribute(SESSION_ATTR_NAME)).isNull();
    }

    @Test
    @DisplayName("unknown or absent state returns null safely")
    void unknownOrAbsentStateReturnsNull() {
        assertThat(repository.loadAuthorizationRequest(request)).isNull();
        assertThat(repository.removeAuthorizationRequest(request, response)).isNull();

        request.setParameter("state", "unknown-state");
        assertThat(repository.loadAuthorizationRequest(request)).isNull();
        assertThat(repository.removeAuthorizationRequest(request, response)).isNull();
    }
}
