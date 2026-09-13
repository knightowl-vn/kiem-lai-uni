package com.universe.novel.domain.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedVoiceDomainTest {

    private static final UUID VOICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-05T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-05T12:00:00Z");

    @Test
    @DisplayName("1. Create valid managed voice with initial state and synthesisRevision = 1")
    void shouldCreateValidManagedVoice() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        assertThat(voice.getId()).isEqualTo(VOICE_ID);
        assertThat(voice.getVoiceKey()).isEqualTo("kiemlai-male-north-01");
        assertThat(voice.getDisplayName()).isEqualTo("Anh Khôi");
        assertThat(voice.getProviderVoiceId()).isEqualTo("minh-duc");
        assertThat(voice.getStatus()).isEqualTo(ManagedVoiceStatus.ACTIVE);
        assertThat(voice.isActive()).isTrue();
        assertThat(voice.getDisplayOrder()).isEqualTo(1);
        assertThat(voice.isDefaultVoice()).isFalse();
        assertThat(voice.getSynthesisRevision()).isEqualTo(1L);
        assertThat(voice.getCreatedAt()).isEqualTo(T0);
        assertThat(voice.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("2. Immutable voiceKey: rejects null, blank, or invalid format")
    void shouldValidateVoiceKey() {
        assertThatThrownBy(() -> ManagedVoice.create(VOICE_ID, null, "Name", "prov-1", 0, false, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedVoice.create(VOICE_ID, "   ", "Name", "prov-1", 0, false, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedVoice.create(VOICE_ID, "invalid key with spaces", "Name", "prov-1", 0, false, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedVoice.create(VOICE_ID, "a", "Name", "prov-1", 0, false, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("3. providerVoiceId change increments synthesisRevision")
    void shouldIncrementSynthesisRevisionWhenProviderVoiceIdChanges() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        assertThat(voice.getSynthesisRevision()).isEqualTo(1L);

        boolean changed = voice.changeProviderMapping("pham-tuyen", T1);

        assertThat(changed).isTrue();
        assertThat(voice.getProviderVoiceId()).isEqualTo("pham-tuyen");
        assertThat(voice.getSynthesisRevision()).isEqualTo(2L);
        assertThat(voice.getUpdatedAt()).isEqualTo(T1);

        boolean changedAgain = voice.changeProviderMapping("mai-anh", T2);
        assertThat(changedAgain).isTrue();
        assertThat(voice.getProviderVoiceId()).isEqualTo("mai-anh");
        assertThat(voice.getSynthesisRevision()).isEqualTo(3L);
        assertThat(voice.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("3b. Changing providerVoiceId to identical value is a no-op")
    void shouldNotIncrementSynthesisRevisionWhenProviderVoiceIdUnchanged() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        boolean changed = voice.changeProviderMapping("minh-duc", T1);

        assertThat(changed).isFalse();
        assertThat(voice.getSynthesisRevision()).isEqualTo(1L);
        assertThat(voice.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("4. Metadata-only changes do not increment synthesisRevision")
    void shouldNotIncrementSynthesisRevisionOnMetadataOrStateChanges() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        voice.updateMetadata("Anh Khôi - Giọng Kể Truyện", T1);
        assertThat(voice.getDisplayName()).isEqualTo("Anh Khôi - Giọng Kể Truyện");
        assertThat(voice.getDisplayOrder()).isEqualTo(1);
        assertThat(voice.getSynthesisRevision()).isEqualTo(1L);
        assertThat(voice.getUpdatedAt()).isEqualTo(T1);

        voice.markDefault(T2);
        assertThat(voice.isDefaultVoice()).isTrue();
        assertThat(voice.getSynthesisRevision()).isEqualTo(1L);

        voice.unmarkDefault(T2);
        assertThat(voice.isDefaultVoice()).isFalse();
        assertThat(voice.getSynthesisRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("5. ACTIVE/DISABLED lifecycle transitions")
    void shouldSupportActiveAndDisabledLifecycle() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        voice.disable(T1);
        assertThat(voice.getStatus()).isEqualTo(ManagedVoiceStatus.DISABLED);
        assertThat(voice.isActive()).isFalse();
        assertThat(voice.getUpdatedAt()).isEqualTo(T1);

        voice.activate(T2);
        assertThat(voice.getStatus()).isEqualTo(ManagedVoiceStatus.ACTIVE);
        assertThat(voice.isActive()).isTrue();
        assertThat(voice.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("6. Default voice must be ACTIVE and cannot be disabled directly")
    void shouldEnforceDefaultVoiceActiveInvariant() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                true,
                T0
        );

        assertThat(voice.isDefaultVoice()).isTrue();

        // Cannot disable while default
        assertThatThrownBy(() -> voice.disable(T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không thể vô hiệu hóa giọng đọc đang là mặc định");

        // Unmarking default allows disable
        voice.unmarkDefault(T1);
        voice.disable(T1);
        assertThat(voice.getStatus()).isEqualTo(ManagedVoiceStatus.DISABLED);

        // Cannot mark a DISABLED voice as default
        assertThatThrownBy(() -> voice.markDefault(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chỉ có thể đặt giọng đọc ACTIVE làm mặc định");
    }

    @Test
    @DisplayName("Rehydration preserves exact persisted state")
    void shouldRehydrateExactPersistedState() {
        ManagedVoice voice = ManagedVoice.rehydrate(
                VOICE_ID,
                "kiemlai-female-north-01",
                "Mai Anh",
                "mai-anh-v2",
                ManagedVoiceStatus.DISABLED,
                3,
                false,
                5L,
                T0,
                T1
        );

        assertThat(voice.getId()).isEqualTo(VOICE_ID);
        assertThat(voice.getVoiceKey()).isEqualTo("kiemlai-female-north-01");
        assertThat(voice.getDisplayName()).isEqualTo("Mai Anh");
        assertThat(voice.getProviderVoiceId()).isEqualTo("mai-anh-v2");
        assertThat(voice.getStatus()).isEqualTo(ManagedVoiceStatus.DISABLED);
        assertThat(voice.getDisplayOrder()).isEqualTo(3);
        assertThat(voice.isDefaultVoice()).isFalse();
        assertThat(voice.getSynthesisRevision()).isEqualTo(5L);
        assertThat(voice.getCreatedAt()).isEqualTo(T0);
        assertThat(voice.getUpdatedAt()).isEqualTo(T1);
    }
}
