package com.universe.novel.infrastructure.persistence.voice;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceKeyAlreadyExistsException;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import jakarta.persistence.Version;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedVoicePersistenceAdapterTest {

    private static final UUID VOICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");

    @Mock
    private SpringDataManagedVoiceJpaRepository repository;

    private ManagedVoicePersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ManagedVoicePersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Find by ID maps entity to domain correctly")
    void shouldFindByIdAndMapToDomain() {
        ManagedVoiceJpaEntity entity = new ManagedVoiceJpaEntity(
                VOICE_ID.toString(),
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                "ACTIVE",
                1,
                true,
                2L,
                T0,
                T0
        );

        when(repository.findById(VOICE_ID.toString())).thenReturn(Optional.of(entity));

        Optional<ManagedVoice> result = adapter.findById(VOICE_ID);

        assertThat(result).isPresent();
        ManagedVoice voice = result.get();
        assertThat(voice.getId()).isEqualTo(VOICE_ID);
        assertThat(voice.getVoiceKey()).isEqualTo("kiemlai-male-north-01");
        assertThat(voice.getDisplayName()).isEqualTo("Anh Khôi");
        assertThat(voice.getProviderVoiceId()).isEqualTo("minh-duc");
        assertThat(voice.getStatus()).isEqualTo(ManagedVoiceStatus.ACTIVE);
        assertThat(voice.getDisplayOrder()).isEqualTo(1);
        assertThat(voice.isDefaultVoice()).isTrue();
        assertThat(voice.getSynthesisRevision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Find by voiceKey maps entity to domain correctly")
    void shouldFindByVoiceKey() {
        ManagedVoiceJpaEntity entity = new ManagedVoiceJpaEntity(
                VOICE_ID.toString(),
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                "ACTIVE",
                1,
                false,
                1L,
                T0,
                T0
        );

        when(repository.findByVoiceKey("kiemlai-male-north-01")).thenReturn(Optional.of(entity));

        Optional<ManagedVoice> result = adapter.findByVoiceKey("kiemlai-male-north-01");
        assertThat(result).isPresent();
        assertThat(result.get().getVoiceKey()).isEqualTo("kiemlai-male-north-01");
    }

    @Test
    @DisplayName("Find default voice")
    void shouldFindDefaultVoice() {
        ManagedVoiceJpaEntity entity = new ManagedVoiceJpaEntity(
                VOICE_ID.toString(),
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                "ACTIVE",
                1,
                true,
                1L,
                T0,
                T0
        );

        when(repository.findByDefaultVoiceTrue()).thenReturn(Optional.of(entity));

        Optional<ManagedVoice> result = adapter.findDefaultVoice();
        assertThat(result).isPresent();
        assertThat(result.get().isDefaultVoice()).isTrue();
    }

    @Test
    @DisplayName("Save new managed voice entity")
    void shouldSaveNewManagedVoice() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        when(repository.findById(VOICE_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ManagedVoiceJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ManagedVoice saved = adapter.save(voice);

        assertThat(saved.getId()).isEqualTo(VOICE_ID);
        assertThat(saved.getVoiceKey()).isEqualTo("kiemlai-male-north-01");
        verify(repository).saveAndFlush(any(ManagedVoiceJpaEntity.class));
    }

    @Test
    @DisplayName("Duplicate voice_key constraint violation translates to ManagedVoiceKeyAlreadyExistsException")
    void shouldTranslateDuplicateKeyException() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry",
                new SQLException("Duplicate entry"),
                "uq_novel_managed_voices_key"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Duplicate key error", cve);

        when(repository.findById(VOICE_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ManagedVoiceJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(voice))
                .isInstanceOf(ManagedVoiceKeyAlreadyExistsException.class)
                .hasMessageContaining("kiemlai-male-north-01");
    }

    @Test
    @DisplayName("Single-default constraint violation translates to ManagedVoiceInvalidStateException")
    void shouldTranslateSingleDefaultConstraintViolation() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-02",
                "Hải Đăng",
                "hai-dang",
                2,
                true,
                T0
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry '1' for key 'uq_novel_managed_voices_single_default'",
                new SQLException("Duplicate entry"),
                "uq_novel_managed_voices_single_default"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Single default violation", cve);

        when(repository.findById(VOICE_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ManagedVoiceJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(voice))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("duy nhất một giọng đọc mặc định");
    }

    @Test
    @DisplayName("Active-default check constraint violation translates to ManagedVoiceInvalidStateException")
    void shouldTranslateActiveDefaultCheckConstraintViolation() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID,
                "kiemlai-male-north-02",
                "Hải Đăng",
                "hai-dang",
                2,
                true,
                T0
        );

        ConstraintViolationException cve = new ConstraintViolationException(
                "Check constraint 'chk_novel_managed_voices_active_default' is violated.",
                new SQLException("Check constraint error"),
                "chk_novel_managed_voices_active_default"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Check constraint violation", cve);

        when(repository.findById(VOICE_ID.toString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ManagedVoiceJpaEntity.class))).thenThrow(dive);

        assertThatThrownBy(() -> adapter.save(voice))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("ManagedVoiceJpaEntity persistenceVersion is annotated with @Version")
    void shouldVerifyOptimisticLockingAnnotationOnJpaEntity() throws NoSuchFieldException {
        Field versionField = ManagedVoiceJpaEntity.class.getDeclaredField("persistenceVersion");
        assertThat(versionField.isAnnotationPresent(Version.class)).isTrue();
    }

    @Test
    @DisplayName("Find all and find all active")
    void shouldFindAllAndFindAllActive() {
        ManagedVoiceJpaEntity entity1 = new ManagedVoiceJpaEntity(VOICE_ID.toString(), "k1", "N1", "p1", "ACTIVE", 1, true, 1L, T0, T0);

        when(repository.findAllByOrderByDisplayOrderAscCreatedAtAsc()).thenReturn(List.of(entity1));
        when(repository.findAllByStatusOrderByDisplayOrderAscCreatedAtAsc("ACTIVE")).thenReturn(List.of(entity1));

        assertThat(adapter.findAll()).hasSize(1);
        assertThat(adapter.findAllActive()).hasSize(1);
    }
}
