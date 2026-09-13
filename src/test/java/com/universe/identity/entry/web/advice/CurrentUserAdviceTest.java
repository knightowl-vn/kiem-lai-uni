package com.universe.identity.entry.web.advice;

import com.universe.identity.application.ports.AuthenticatedRequestIdentityQueryPort;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.contracts.currentuser.CurrentUserView;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.shared.security.AuthenticatedEmailResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CurrentUserAdviceTest {

    private static final String EMAIL = "reader@universe.local";

    private final CurrentUserQueryPort currentUserQueryPort =
            mock(CurrentUserQueryPort.class);

    private final AuthenticatedEmailResolver emailResolver =
            mock(AuthenticatedEmailResolver.class);

    private final CurrentUserAdvice advice =
            new CurrentUserAdvice(currentUserQueryPort, emailResolver);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validSnapshotMapsNavbarViewWithoutAnotherIdentityQuery() throws Exception {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                userId,
                EMAIL,
                "Reader",
                "/media/avatar",
                UserStatus.ACTIVE,
                UserRole.USER
        );
        AuthenticatedRequestIdentityQueryPort queryPort =
                mock(AuthenticatedRequestIdentityQueryPort.class);
        when(queryPort.findByEmail(EMAIL)).thenReturn(Optional.of(identity));
        AccountStatusFilter filter = new AccountStatusFilter(queryPort);
        Authentication authentication = authentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        CurrentUserView view = advice.currentUser(request, authentication);

        assertThat(view.id()).isEqualTo(userId.toString());
        assertThat(view.email()).isEqualTo(EMAIL);
        assertThat(view.displayName()).isEqualTo("Reader");
        assertThat(view.avatarUrl()).isEqualTo("/media/avatar");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.role()).isEqualTo("USER");
        assertThat(view.bio()).isNull();
        assertThat(view.createdAt()).isNull();
        assertThat(view.authProvider()).isNull();
        assertThat(view.hasLocalPassword()).isFalse();
        verifyNoInteractions(currentUserQueryPort, emailResolver);
    }

    @Test
    void anonymousRequestKeepsNullCurrentUserBehavior() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(emailResolver.resolve(null)).thenReturn(Optional.empty());

        assertThat(advice.currentUser(request, null)).isNull();
        verifyNoInteractions(currentUserQueryPort);
    }

    @Test
    void snapshotAbsentPreservesExistingFallbackForSkippedFilterRoutes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login");
        Authentication authentication = authentication();
        CurrentUserView expected = new CurrentUserView(
                UUID.randomUUID().toString(),
                EMAIL,
                "Reader",
                null,
                "profile bio",
                "ACTIVE",
                "USER",
                "LOCAL",
                Instant.now(),
                true
        );
        when(emailResolver.resolve(authentication)).thenReturn(Optional.of(EMAIL));
        when(currentUserQueryPort.findByEmail(EMAIL)).thenReturn(Optional.of(expected));

        assertThat(advice.currentUser(request, authentication)).isSameAs(expected);
        verify(currentUserQueryPort).findByEmail(EMAIL);
    }

    private Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(
                EMAIL,
                "ignored",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
