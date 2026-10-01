package com.universe.community.infrastructure.identity;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdentityCommunityAuthorProfileAdapter Unit Tests")
class IdentityCommunityAuthorProfileAdapterTest {

    @Mock
    private UserIdentityContract userIdentityContract;

    private IdentityCommunityAuthorProfileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityCommunityAuthorProfileAdapter(userIdentityContract);
    }

    @Test
    @DisplayName("Should throw NullPointerException when UserIdentityContract is null")
    void shouldThrowWhenConstructorArgIsNull() {
        assertThatThrownBy(() -> new IdentityCommunityAuthorProfileAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("UserIdentityContract cannot be null.");
    }

    @Test
    @DisplayName("Should successfully map UserPublicProfileDetailsDTO to CommunityAuthorProfileDetails")
    void shouldMapActiveUserPublicProfileDetailsSuccessfully() {
        UUID userId = UUID.randomUUID();
        UserPublicProfileDetailsDTO userProfile = new UserPublicProfileDetailsDTO(
                userId,
                "Alice Wonderland",
                "https://cdn.example.com/alice.png",
                "alice_wonderland",
                "Writer and reader."
        );

        when(userIdentityContract.findPublicProfileDetailsByHandle("alice_wonderland"))
                .thenReturn(Optional.of(userProfile));

        Optional<CommunityAuthorProfileDetails> resultOpt = adapter.findAuthorProfileByHandle("alice_wonderland");

        assertThat(resultOpt).isPresent();
        CommunityAuthorProfileDetails details = resultOpt.get();
        assertThat(details.userId()).isEqualTo(userId);
        assertThat(details.publicHandle()).isEqualTo("alice_wonderland");
        assertThat(details.displayName()).isEqualTo("Alice Wonderland");
        assertThat(details.avatarUrl()).isEqualTo("https://cdn.example.com/alice.png");
        assertThat(details.bio()).isEqualTo("Writer and reader.");

        verify(userIdentityContract).findPublicProfileDetailsByHandle("alice_wonderland");
    }

    @Test
    @DisplayName("Should preserve null avatarUrl and null bio during mapping")
    void shouldPreserveNullAvatarUrlAndBio() {
        UUID userId = UUID.randomUUID();
        UserPublicProfileDetailsDTO userProfile = new UserPublicProfileDetailsDTO(
                userId,
                "Bob The Builder",
                null,
                "bob_builder",
                null
        );

        when(userIdentityContract.findPublicProfileDetailsByHandle("bob_builder"))
                .thenReturn(Optional.of(userProfile));

        Optional<CommunityAuthorProfileDetails> resultOpt = adapter.findAuthorProfileByHandle("bob_builder");

        assertThat(resultOpt).isPresent();
        CommunityAuthorProfileDetails details = resultOpt.get();
        assertThat(details.userId()).isEqualTo(userId);
        assertThat(details.publicHandle()).isEqualTo("bob_builder");
        assertThat(details.displayName()).isEqualTo("Bob The Builder");
        assertThat(details.avatarUrl()).isNull();
        assertThat(details.bio()).isNull();
    }

    @Test
    @DisplayName("Should delegate raw handle directly to Identity contract without Community-side normalization")
    void shouldDelegateRawHandleDirectlyToIdentityContract() {
        UUID userId = UUID.randomUUID();
        UserPublicProfileDetailsDTO userProfile = new UserPublicProfileDetailsDTO(
                userId,
                "Charlie",
                null,
                "charlie",
                "Hello world"
        );

        when(userIdentityContract.findPublicProfileDetailsByHandle("@Alice"))
                .thenReturn(Optional.of(userProfile));

        Optional<CommunityAuthorProfileDetails> resultOpt = adapter.findAuthorProfileByHandle("@Alice");

        assertThat(resultOpt).isPresent();
        verify(userIdentityContract).findPublicProfileDetailsByHandle("@Alice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("Should return Optional.empty() without querying Identity when handle is empty or blank")
    void shouldReturnEmptyForBlankOrInvalidHandles(String handle) {
        Optional<CommunityAuthorProfileDetails> resultOpt = adapter.findAuthorProfileByHandle(handle);

        assertThat(resultOpt).isEmpty();
        verifyNoInteractions(userIdentityContract);
    }

    @Test
    @DisplayName("Should return Optional.empty() without querying Identity when handle is null")
    void shouldReturnEmptyWhenHandleIsNull() {
        Optional<CommunityAuthorProfileDetails> resultOpt = adapter.findAuthorProfileByHandle(null);

        assertThat(resultOpt).isEmpty();
        verifyNoInteractions(userIdentityContract);
    }

    @Test
    @DisplayName("Should return Optional.empty() when Identity contract returns empty")
    void shouldReturnEmptyWhenIdentityContractReturnsEmpty() {
        when(userIdentityContract.findPublicProfileDetailsByHandle("nonexistent"))
                .thenReturn(Optional.empty());

        Optional<CommunityAuthorProfileDetails> resultOpt = adapter.findAuthorProfileByHandle("nonexistent");

        assertThat(resultOpt).isEmpty();
        verify(userIdentityContract).findPublicProfileDetailsByHandle("nonexistent");
    }

    // =========================================================================
    // findAuthorProfilesByIds Tests
    // =========================================================================

    @Test
    @DisplayName("findAuthorProfilesByIds: Should return empty map when userIds is null or empty")
    void shouldReturnEmptyMapWhenUserIdsIsNullOrEmpty() {
        assertThat(adapter.findAuthorProfilesByIds(null)).isEmpty();
        assertThat(adapter.findAuthorProfilesByIds(java.util.Set.of())).isEmpty();
        verifyNoInteractions(userIdentityContract);
    }

    @Test
    @DisplayName("findAuthorProfilesByIds: Should map UserPublicProfileDTO map to CommunityAuthorProfileSummary map")
    void shouldMapUserPublicProfilesSuccessfully() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        com.universe.identity.contracts.dto.UserPublicProfileDTO p1 = new com.universe.identity.contracts.dto.UserPublicProfileDTO(
                u1, "User One", "https://cdn.example.com/u1.jpg", "handle_one"
        );
        com.universe.identity.contracts.dto.UserPublicProfileDTO p2 = new com.universe.identity.contracts.dto.UserPublicProfileDTO(
                u2, "User Two", null, "handle_two"
        );

        when(userIdentityContract.findPublicProfilesByIds(java.util.Set.of(u1, u2)))
                .thenReturn(java.util.Map.of(u1, p1, u2, p2));

        java.util.Map<UUID, com.universe.community.application.port.out.CommunityAuthorProfileSummary> result =
                adapter.findAuthorProfilesByIds(java.util.Set.of(u1, u2));

        assertThat(result).hasSize(2);
        assertThat(result.get(u1)).satisfies(s -> {
            assertThat(s.userId()).isEqualTo(u1);
            assertThat(s.publicHandle()).isEqualTo("handle_one");
            assertThat(s.displayName()).isEqualTo("User One");
            assertThat(s.avatarUrl()).isEqualTo("https://cdn.example.com/u1.jpg");
        });
        assertThat(result.get(u2)).satisfies(s -> {
            assertThat(s.userId()).isEqualTo(u2);
            assertThat(s.publicHandle()).isEqualTo("handle_two");
            assertThat(s.displayName()).isEqualTo("User Two");
            assertThat(s.avatarUrl()).isNull();
        });
    }
}
