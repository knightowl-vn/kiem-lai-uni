package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenFranchiseMembership Pure Domain Tests")
class ScreenFranchiseMembershipTest {

    @Test
    @DisplayName("Successfully creates ScreenFranchiseMembership with valid scalar IDs and displayOrder")
    void shouldCreateMembershipWithValidInputs() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);

        assertThat(membership.getId()).isEqualTo(id);
        assertThat(membership.getFranchiseId()).isEqualTo(franchiseId);
        assertThat(membership.getTitleId()).isEqualTo(titleId);
        assertThat(membership.getDisplayOrder()).isZero();
    }

    @Test
    @DisplayName("Factory method create produces identical valid membership")
    void shouldCreateViaFactoryMethod() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenFranchiseMembership membership = ScreenFranchiseMembership.create(id, franchiseId, titleId, 5);

        assertThat(membership.getId()).isEqualTo(id);
        assertThat(membership.getFranchiseId()).isEqualTo(franchiseId);
        assertThat(membership.getTitleId()).isEqualTo(titleId);
        assertThat(membership.getDisplayOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("Rejects null membership ID")
    void shouldRejectNullId() {
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        assertThatThrownBy(() -> new ScreenFranchiseMembership(null, franchiseId, titleId, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Membership ID cannot be null");
    }

    @Test
    @DisplayName("Rejects null franchise ID")
    void shouldRejectNullFranchiseId() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        assertThatThrownBy(() -> new ScreenFranchiseMembership(id, null, titleId, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Franchise ID cannot be null");
    }

    @Test
    @DisplayName("Rejects null title ID")
    void shouldRejectNullTitleId() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();

        assertThatThrownBy(() -> new ScreenFranchiseMembership(id, franchiseId, null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Title ID cannot be null");
    }

    @Test
    @DisplayName("Rejects negative display order in constructor")
    void shouldRejectNegativeDisplayOrderInConstructor() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        assertThatThrownBy(() -> new ScreenFranchiseMembership(id, franchiseId, titleId, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Display order cannot be negative: -1");
    }

    @Test
    @DisplayName("Updates display order with non-negative integer and rejects negative")
    void shouldUpdateDisplayOrderAndRejectNegative() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchiseId, titleId, 0);

        membership.updateDisplayOrder(10);
        assertThat(membership.getDisplayOrder()).isEqualTo(10);

        membership.updateDisplayOrder(0);
        assertThat(membership.getDisplayOrder()).isZero();

        assertThatThrownBy(() -> membership.updateDisplayOrder(-2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Display order cannot be negative: -2");
        assertThat(membership.getDisplayOrder()).isZero();
    }

    @Test
    @DisplayName("Implements identity-based equals and hashCode")
    void shouldImplementIdentityBasedEquality() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(id, franchiseId, titleId, 1);
        ScreenFranchiseMembership m2 = new ScreenFranchiseMembership(id, UUID.randomUUID(), UUID.randomUUID(), 2);
        ScreenFranchiseMembership m3 = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, titleId, 1);

        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
        assertThat(m1).isNotEqualTo(m3);
    }
}
