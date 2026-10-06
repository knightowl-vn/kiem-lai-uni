package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenFranchiseMembership;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenFranchiseMembershipPersistenceMapper Unit Tests")
class ScreenFranchiseMembershipPersistenceMapperTest {

    @Test
    @DisplayName("Should correctly map membership domain to JPA entity and back")
    void shouldMapMembershipRoundTrip() {
        UUID id = UUID.randomUUID();
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        int displayOrder = 3;

        ScreenFranchiseMembership domain = new ScreenFranchiseMembership(id, franchiseId, titleId, displayOrder);

        ScreenFranchiseMembershipJpaEntity entity = ScreenFranchiseMembershipPersistenceMapper.toEntity(domain);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(id.toString());
        assertThat(entity.getFranchiseId()).isEqualTo(franchiseId.toString());
        assertThat(entity.getTitleId()).isEqualTo(titleId.toString());
        assertThat(entity.getDisplayOrder()).isEqualTo(3);

        ScreenFranchiseMembership rehydrated = ScreenFranchiseMembershipPersistenceMapper.toDomain(entity);

        assertThat(rehydrated.getId()).isEqualTo(id);
        assertThat(rehydrated.getFranchiseId()).isEqualTo(franchiseId);
        assertThat(rehydrated.getTitleId()).isEqualTo(titleId);
        assertThat(rehydrated.getDisplayOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should update existing JPA entity from domain")
    void shouldUpdateEntityFromDomain() {
        UUID id = UUID.randomUUID();
        UUID franchiseId1 = UUID.randomUUID();
        UUID franchiseId2 = UUID.randomUUID();
        UUID titleId1 = UUID.randomUUID();
        UUID titleId2 = UUID.randomUUID();

        ScreenFranchiseMembershipJpaEntity entity = new ScreenFranchiseMembershipJpaEntity(
                id.toString(),
                franchiseId1.toString(),
                titleId1.toString(),
                0
        );

        ScreenFranchiseMembership updated = new ScreenFranchiseMembership(id, franchiseId2, titleId2, 5);
        ScreenFranchiseMembershipPersistenceMapper.updateEntity(entity, updated);

        assertThat(entity.getFranchiseId()).isEqualTo(franchiseId2.toString());
        assertThat(entity.getTitleId()).isEqualTo(titleId2.toString());
        assertThat(entity.getDisplayOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should fail rehydration explicitly when entity contains negative displayOrder")
    void shouldFailRehydrationOnNegativeDisplayOrder() {
        ScreenFranchiseMembershipJpaEntity entity = new ScreenFranchiseMembershipJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                -1
        );

        assertThatThrownBy(() -> ScreenFranchiseMembershipPersistenceMapper.toDomain(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Display order cannot be negative");
    }
}
