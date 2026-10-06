package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenFranchise Pure Domain Tests")
class ScreenFranchiseTest {

    @Test
    @DisplayName("Successfully creates ScreenFranchise with valid inputs")
    void shouldCreateFranchiseWithValidInputs() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");

        assertThat(franchise.getId()).isEqualTo(id);
        assertThat(franchise.getSlug()).isEqualTo("kiem-lai");
        assertThat(franchise.getName()).isEqualTo("Kiếm Lai");
    }

    @Test
    @DisplayName("Factory method create produces identical valid ScreenFranchise")
    void shouldCreateViaFactoryMethod() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = ScreenFranchise.create(id, "kiem-lai", "Kiếm Lai");

        assertThat(franchise.getId()).isEqualTo(id);
        assertThat(franchise.getSlug()).isEqualTo("kiem-lai");
        assertThat(franchise.getName()).isEqualTo("Kiếm Lai");
    }

    @Test
    @DisplayName("Rejects null franchise ID")
    void shouldRejectNullId() {
        assertThatThrownBy(() -> new ScreenFranchise(null, "kiem-lai", "Kiếm Lai"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Franchise ID cannot be null");
    }

    @Test
    @DisplayName("Rejects null slug")
    void shouldRejectNullSlug() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ScreenFranchise(id, null, "Kiếm Lai"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Slug cannot be null");
    }

    @Test
    @DisplayName("Rejects blank slug")
    void shouldRejectBlankSlug() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ScreenFranchise(id, "   ", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Slug cannot be blank");
    }

    @Test
    @DisplayName("Normalizes slug by trimming whitespace and lowercasing")
    void shouldNormalizeSlug() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "  Kiem-Lai  ", "Kiếm Lai");

        assertThat(franchise.getSlug()).isEqualTo("kiem-lai");
    }

    @Test
    @DisplayName("Rejects invalid slug characters or formats")
    void shouldRejectInvalidSlugFormats() {
        UUID id = UUID.randomUUID();

        // Spaces
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiem lai", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class);

        // Leading hyphen
        assertThatThrownBy(() -> new ScreenFranchise(id, "-kiem-lai", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class);

        // Trailing hyphen
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiem-lai-", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class);

        // Consecutive hyphens
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiem--lai", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class);

        // Special characters
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiem_lai", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class);

        // Diacritics in slug
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiếm-lai", "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class);

        // Exceeding max length (180)
        String tooLong = "a".repeat(181);
        assertThatThrownBy(() -> new ScreenFranchise(id, tooLong, "Kiếm Lai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 180");
    }

    @Test
    @DisplayName("Rejects null franchise name")
    void shouldRejectNullName() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiem-lai", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Franchise name cannot be null");
    }

    @Test
    @DisplayName("Rejects blank franchise name")
    void shouldRejectBlankName() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ScreenFranchise(id, "kiem-lai", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Franchise name cannot be blank");
    }

    @Test
    @DisplayName("Normalizes display name outer whitespace while preserving casing and diacritics")
    void shouldNormalizeNameWhitespaceWithoutModifyingContent() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "  Kiếm Lai (Đại Lục)  ");

        assertThat(franchise.getName()).isEqualTo("Kiếm Lai (Đại Lục)");
    }

    @Test
    @DisplayName("Updates name and slug via domain methods")
    void shouldUpdateNameAndSlug() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");

        franchise.update("kiem-lai-universe", "  Kiếm Lai Vũ Trụ  ");
        assertThat(franchise.getSlug()).isEqualTo("kiem-lai-universe");
        assertThat(franchise.getName()).isEqualTo("Kiếm Lai Vũ Trụ");

        franchise.rename("Kiếm Lai Series");
        assertThat(franchise.getName()).isEqualTo("Kiếm Lai Series");

        franchise.changeSlug("kiem-lai-series");
        assertThat(franchise.getSlug()).isEqualTo("kiem-lai-series");
    }

    @Test
    @DisplayName("Failed update is atomic: preserves original slug and name when name validation fails")
    void shouldPreserveStateWhenUpdateFailsAtomicity() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");

        assertThatThrownBy(() -> franchise.update("new-valid-slug", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Franchise name cannot be blank");

        assertThat(franchise.getSlug()).isEqualTo("kiem-lai");
        assertThat(franchise.getName()).isEqualTo("Kiếm Lai");
    }

    @Test
    @DisplayName("Implements identity-based equals and hashCode")
    void shouldImplementIdentityBasedEquality() {
        UUID id = UUID.randomUUID();
        ScreenFranchise f1 = new ScreenFranchise(id, "kiem-lai", "Kiếm Lai");
        ScreenFranchise f2 = new ScreenFranchise(id, "other-slug", "Other Name");
        ScreenFranchise f3 = new ScreenFranchise(UUID.randomUUID(), "kiem-lai", "Kiếm Lai");

        assertThat(f1).isEqualTo(f2);
        assertThat(f1.hashCode()).isEqualTo(f2.hashCode());
        assertThat(f1).isNotEqualTo(f3);
    }
}
