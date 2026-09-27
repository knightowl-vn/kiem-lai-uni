package com.universe.identity.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2ReturnToStoreTest {

    private OAuth2ReturnToStore store;
    private MockHttpSession session;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        store = new OAuth2ReturnToStore();
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
    }

    @Test
    @DisplayName("store and consume returns returnTo and removes it for one-time use")
    void storeAndConsumeReturnsValueAndRemovesIt() {
        store.store(session, "state-123", "/novel/chapters/quyen-1-chuong-1");

        Optional<String> firstConsume = store.consume(session, "state-123");
        assertThat(firstConsume).contains("/novel/chapters/quyen-1-chuong-1");

        Optional<String> secondConsume = store.consume(session, "state-123");
        assertThat(secondConsume).isEmpty();
    }

    @Test
    @DisplayName("consuming with unknown or absent state returns empty")
    void consumeWithUnknownStateReturnsEmpty() {
        store.store(session, "state-1", "/novel");

        assertThat(store.consume(session, "state-unknown")).isEmpty();
        assertThat(store.consume(session, null)).isEmpty();
        assertThat(store.consume(session, "")).isEmpty();
        assertThat(store.consume(session, "   ")).isEmpty();
    }

    @Test
    @DisplayName("store handles null and blank arguments safely without errors")
    void storeHandlesNullAndBlankArgumentsSafely() {
        store.store(session, null, "/novel");
        store.store(session, "", "/novel");
        store.store(session, "state-1", null);
        store.store(session, "state-2", "");
        store.store(session, "state-3", "   ");

        assertThat(store.consume(session, "state-1")).isEmpty();
        assertThat(store.consume(session, "state-2")).isEmpty();
        assertThat(store.consume(session, "state-3")).isEmpty();
        assertThat(session.getAttribute("KIEMLAI_OAUTH2_RETURN_TO_STORE")).isNull();
    }

    @Test
    @DisplayName("multi-tab concurrency allows independent states in the same session")
    void multiTabConcurrencyAllowsIndependentStates() {
        store.store(session, "tab-1-state", "/novel/chapters/ch-1?discussionBlock=b1");
        store.store(session, "tab-2-state", "/wiki/articles/tran-binh-an");
        store.store(session, "tab-3-state", "/novel/chapters/ch-2");

        assertThat(store.consume(session, "tab-2-state")).contains("/wiki/articles/tran-binh-an");
        assertThat(store.consume(session, "tab-1-state")).contains("/novel/chapters/ch-1?discussionBlock=b1");
        assertThat(store.consume(session, "tab-3-state")).contains("/novel/chapters/ch-2");
    }

    @Test
    @DisplayName("bounded capacity of 10 entries evicts the oldest entry on 11th insertion")
    void boundedCapacityEvictsOldestEntry() {
        for (int i = 0; i < 10; i++) {
            store.store(session, "state-" + i, "/novel/page-" + i);
        }

        // 11th entry
        store.store(session, "state-10", "/novel/page-10");

        // Oldest (state-0) must have been evicted
        assertThat(store.consume(session, "state-0")).isEmpty();

        // Other entries (state-1 through state-10) must remain
        for (int i = 1; i <= 10; i++) {
            assertThat(store.consume(session, "state-" + i)).contains("/novel/page-" + i);
        }
    }

    @Test
    @DisplayName("store and consume using HttpServletRequest wrapper")
    void storeAndConsumeUsingHttpServletRequest() {
        store.store(request, "state-req", "/novel/chapters/quyen-1");

        Optional<String> consumed = store.consume(request, "state-req");
        assertThat(consumed).contains("/novel/chapters/quyen-1");
        assertThat(store.consume(request, "state-req")).isEmpty();
    }

    @Test
    @DisplayName("consuming the last entry cleans up session attribute")
    void consumingLastEntryCleansUpSessionAttribute() {
        store.store(session, "single-state", "/novel");
        assertThat(session.getAttribute("KIEMLAI_OAUTH2_RETURN_TO_STORE")).isNotNull();

        store.consume(session, "single-state");
        assertThat(session.getAttribute("KIEMLAI_OAUTH2_RETURN_TO_STORE")).isNull();
    }

    @Test
    @DisplayName("peek returns value without consuming or removing it")
    void peekReturnsValueWithoutRemoving() {
        store.store(session, "peek-state", "/novel/peek");

        assertThat(store.peek(session, "peek-state")).contains("/novel/peek");
        assertThat(store.peek(session, "peek-state")).contains("/novel/peek");
        assertThat(store.consume(session, "peek-state")).contains("/novel/peek");
        assertThat(store.peek(session, "peek-state")).isEmpty();
    }
}
