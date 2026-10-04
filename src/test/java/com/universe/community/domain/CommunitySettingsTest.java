package com.universe.community.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunitySettings Domain Entity Tests")
class CommunitySettingsTest {

    @Test
    @DisplayName("defaultSettings() returns singleton DEFAULT with AUTO_PUBLISH and version 0")
    void shouldCreateDefaultSettings() {
        CommunitySettings settings = CommunitySettings.defaultSettings();

        assertThat(settings.getId()).isEqualTo(CommunitySettings.SINGLETON_ID);
        assertThat(settings.getPublicationMode()).isEqualTo(CommunityPublicationMode.AUTO_PUBLISH);
        assertThat(settings.getVersion()).isEqualTo(0L);
        assertThat(settings.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(settings.getUpdatedByUserId()).isNull();
    }

    @Test
    @DisplayName("Rejects non-DEFAULT id")
    void shouldRejectNonDefaultId() {
        assertThatThrownBy(() -> new CommunitySettings(
                "CUSTOM_ID",
                CommunityPublicationMode.AUTO_PUBLISH,
                1L,
                Instant.now(),
                UUID.randomUUID()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEFAULT");
    }

    @Test
    @DisplayName("Rejects null attributes for mode and updatedAt; withPublicationMode rejects null actor")
    void shouldRejectNullAttributes() {
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> new CommunitySettings(CommunitySettings.SINGLETON_ID, null, 1L, now, actorId))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CommunitySettings(CommunitySettings.SINGLETON_ID, CommunityPublicationMode.AUTO_PUBLISH, 1L, null, actorId))
                .isInstanceOf(NullPointerException.class);

        // Constructor allows null updatedByUserId for system/bootstrap initialization
        CommunitySettings bootstrap = new CommunitySettings(CommunitySettings.SINGLETON_ID, CommunityPublicationMode.AUTO_PUBLISH, 0L, now, null);
        assertThat(bootstrap.getUpdatedByUserId()).isNull();

        // withPublicationMode strictly requires non-null actorUserId
        assertThatThrownBy(() -> bootstrap.withPublicationMode(CommunityPublicationMode.PRE_MODERATION, null, now))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("withPublicationMode() increments version and updates mode, actor, and timestamp")
    void shouldAdvanceModeAndVersion() {
        CommunitySettings initial = CommunitySettings.defaultSettings();
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-04T12:00:00Z");

        CommunitySettings updated = initial.withPublicationMode(CommunityPublicationMode.PRE_MODERATION, adminId, now);

        assertThat(updated.getId()).isEqualTo(CommunitySettings.SINGLETON_ID);
        assertThat(updated.getPublicationMode()).isEqualTo(CommunityPublicationMode.PRE_MODERATION);
        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getUpdatedByUserId()).isEqualTo(adminId);
        assertThat(updated.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("equals and hashCode contract")
    void shouldSatisfyEqualsAndHashCode() {
        UUID adminId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-04T12:00:00Z");

        CommunitySettings s1 = CommunitySettings.of(CommunityPublicationMode.PRE_MODERATION, 1L, now, adminId);
        CommunitySettings s2 = CommunitySettings.of(CommunityPublicationMode.PRE_MODERATION, 1L, now, adminId);
        CommunitySettings s3 = CommunitySettings.of(CommunityPublicationMode.AUTO_PUBLISH, 1L, now, adminId);

        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
        assertThat(s1).isNotEqualTo(s3);
    }
}
