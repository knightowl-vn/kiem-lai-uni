package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenFranchise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenFranchisePersistenceMapper Unit Tests")
class ScreenFranchisePersistenceMapperTest {

    @Test
    @DisplayName("Should correctly map domain to JPA entity")
    void shouldMapDomainToEntity() {
        UUID id = UUID.randomUUID();
        ScreenFranchise domain = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");

        ScreenFranchiseJpaEntity entity = ScreenFranchisePersistenceMapper.toEntity(domain);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(id.toString());
        assertThat(entity.getSlug()).isEqualTo("kiem-lai");
        assertThat(entity.getName()).isEqualTo("Kiếm Lai");
    }

    @Test
    @DisplayName("Should correctly map JPA entity to domain")
    void shouldMapEntityToDomain() {
        UUID id = UUID.randomUUID();
        ScreenFranchiseJpaEntity entity = new ScreenFranchiseJpaEntity(id.toString(), "kiem-lai-2", "Kiếm Lai Phần 2");

        ScreenFranchise domain = ScreenFranchisePersistenceMapper.toDomain(entity);

        assertThat(domain).isNotNull();
        assertThat(domain.getId()).isEqualTo(id);
        assertThat(domain.getSlug()).isEqualTo("kiem-lai-2");
        assertThat(domain.getName()).isEqualTo("Kiếm Lai Phần 2");
    }

    @Test
    @DisplayName("Should update existing JPA entity from domain")
    void shouldUpdateEntityFromDomain() {
        UUID id = UUID.randomUUID();
        ScreenFranchiseJpaEntity entity = new ScreenFranchiseJpaEntity(id.toString(), "kiem-lai-old", "Kiếm Lai Cũ");
        ScreenFranchise domain = new ScreenFranchise(id, "kiem-lai-new", "Kiếm Lai Mới");

        ScreenFranchisePersistenceMapper.updateEntity(entity, domain);

        assertThat(entity.getId()).isEqualTo(id.toString());
        assertThat(entity.getSlug()).isEqualTo("kiem-lai-new");
        assertThat(entity.getName()).isEqualTo("Kiếm Lai Mới");
    }

    @Test
    @DisplayName("Should throw NullPointerException when input is null")
    void shouldThrowWhenInputIsNull() {
        assertThatThrownBy(() -> ScreenFranchisePersistenceMapper.toEntity(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");

        assertThatThrownBy(() -> ScreenFranchisePersistenceMapper.toDomain(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");

        assertThatThrownBy(() -> ScreenFranchisePersistenceMapper.updateEntity(null, new ScreenFranchise(UUID.randomUUID(), "test", "Test")))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> ScreenFranchisePersistenceMapper.updateEntity(new ScreenFranchiseJpaEntity(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
