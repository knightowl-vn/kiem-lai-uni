package com.universe.identity.infrastructure.security;

import com.universe.identity.application.ports.AuthenticatedRequestIdentityQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountStatusFilterTest {

    private static final String EMAIL = "reader@universe.local";

    private final AuthenticatedRequestIdentityQueryPort queryPort =
            mock(AuthenticatedRequestIdentityQueryPort.class);

    private final AccountStatusFilter filter = new AccountStatusFilter(queryPort);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeAccountWithMatchingRoleAttachesSnapshotAndContinues() throws Exception {
        AuthenticatedRequestIdentity identity = identity(UserStatus.ACTIVE, UserRole.USER);
        when(queryPort.findByEmail(EMAIL)).thenReturn(Optional.of(identity));
        authenticate("ROLE_USER");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(continued));

        assertThat(continued).isTrue();
        assertThat(AuthenticatedRequestIdentityAccessor.find(request)).contains(identity);
        verify(queryPort).findByEmail(EMAIL);
    }

    @Test
    void missingAccountPreservesInvalidationAndRedirectWithoutSnapshot() throws Exception {
        when(queryPort.findByEmail(EMAIL)).thenReturn(Optional.empty());
        authenticate("ROLE_USER");
        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(continued));

        assertRejected(request, response, continued, "/login?accountNotFound");
    }

    @Test
    void blockedAccountPreservesBlockedRedirectWithoutSnapshot() throws Exception {
        when(queryPort.findByEmail(EMAIL))
                .thenReturn(Optional.of(identity(UserStatus.BLOCKED, UserRole.USER)));
        authenticate("ROLE_USER");
        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(continued));

        assertRejected(request, response, continued, "/login?blocked");
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"UNVERIFIED", "BANNED"})
    void otherNonActiveAccountPreservesDisabledRedirectWithoutSnapshot(
            UserStatus status
    ) throws Exception {
        when(queryPort.findByEmail(EMAIL))
                .thenReturn(Optional.of(identity(status, UserRole.USER)));
        authenticate("ROLE_USER");
        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(continued));

        assertRejected(request, response, continued, "/login?disabled");
    }

    @Test
    void roleMismatchPreservesPermissionsChangedRedirectWithoutSnapshot() throws Exception {
        when(queryPort.findByEmail(EMAIL))
                .thenReturn(Optional.of(identity(UserStatus.ACTIVE, UserRole.ADMIN)));
        authenticate("ROLE_USER");
        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(continued));

        assertRejected(request, response, continued, "/login?permissionsChanged");
    }

    @Test
    void anonymousRequestDoesNotQueryOrAttachIdentity() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken(
                        "key",
                        "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
                )
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                recordingChain(continued)
        );

        assertThat(continued).isTrue();
        assertThat(AuthenticatedRequestIdentityAccessor.find(request)).isEmpty();
        verifyNoInteractions(queryPort);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        EMAIL,
                        "ignored",
                        List.of(new SimpleGrantedAuthority(authority))
                )
        );
    }

    private MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        return request;
    }

    private FilterChain recordingChain(AtomicBoolean continued) {
        return (request, response) -> continued.set(true);
    }

    private AuthenticatedRequestIdentity identity(
            UserStatus status,
            UserRole role
    ) {
        return new AuthenticatedRequestIdentity(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                EMAIL,
                "Reader",
                null,
                status,
                role
        );
    }

    private void assertRejected(
            MockHttpServletRequest request,
            MockHttpServletResponse response,
            AtomicBoolean continued,
            String redirect
    ) {
        assertThat(continued).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo(redirect);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(AuthenticatedRequestIdentityAccessor.find(request)).isEmpty();
        assertThat(request.getSession(false)).isNull();
        assertExpiredAuthenticationCookies(response);
        verify(queryPort).findByEmail(EMAIL);
    }

    private void assertExpiredAuthenticationCookies(
            MockHttpServletResponse response
    ) {
        assertThat(response.getCookies())
                .extracting(cookie -> cookie.getName() + ":" + cookie.getMaxAge())
                .containsExactly("JSESSIONID:0", "remember-me:0");
    }
}
