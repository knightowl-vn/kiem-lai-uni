package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedRequestIdentityQueryAdapterTest {

    private final SpringDataUserJpaRepository repository =
            mock(SpringDataUserJpaRepository.class);

    private final AuthenticatedRequestIdentityQueryAdapter adapter =
            new AuthenticatedRequestIdentityQueryAdapter(repository);

    @Test
    void mapsActiveAccountProjectionToApprovedRequestIdentityFields() {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AuthenticatedRequestIdentityProjection projection =
                mock(AuthenticatedRequestIdentityProjection.class);

        when(projection.getUserId()).thenReturn(userId.toString());
        when(projection.getNormalizedEmail()).thenReturn("reader@universe.local");
        when(projection.getDisplayName()).thenReturn("Reader");
        when(projection.getAvatarUrl()).thenReturn("/media/avatar");
        when(projection.getStatus()).thenReturn("ACTIVE");
        when(projection.getRole()).thenReturn(UserRole.USER);
        when(repository.findRequestIdentityByEmail("reader@universe.local"))
                .thenReturn(Optional.of(projection));

        AuthenticatedRequestIdentity identity = adapter
                .findByEmail("  READER@UNIVERSE.LOCAL ")
                .orElseThrow();

        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.normalizedEmail()).isEqualTo("reader@universe.local");
        assertThat(identity.displayName()).isEqualTo("Reader");
        assertThat(identity.avatarUrl()).isEqualTo("/media/avatar");
        assertThat(identity.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(identity.role()).isEqualTo(UserRole.USER);
        verify(repository).findRequestIdentityByEmail("reader@universe.local");
    }

    @Test
    void blankEmailReturnsEmptyWithoutPersistenceLookup() {
        assertThat(adapter.findByEmail("   ")).isEmpty();
        verify(repository, never()).findRequestIdentityByEmail("   ");
    }

    @Test
    void requestIdentityContractContainsOnlyTheSixApprovedFields() {
        assertThat(Arrays.stream(AuthenticatedRequestIdentity.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "userId",
                        "normalizedEmail",
                        "displayName",
                        "avatarUrl",
                        "status",
                        "role"
                );

        assertThat(Arrays.stream(AuthenticatedRequestIdentityProjection.class.getMethods())
                .map(method -> method.getName()))
                .containsExactlyInAnyOrder(
                        "getUserId",
                        "getNormalizedEmail",
                        "getDisplayName",
                        "getAvatarUrl",
                        "getStatus",
                        "getRole"
                );
    }
}
