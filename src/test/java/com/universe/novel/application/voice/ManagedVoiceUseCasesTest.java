package com.universe.novel.application.voice;

import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceKeyAlreadyExistsException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.voice.commands.ChangeManagedVoiceProviderMappingCommand;
import com.universe.novel.application.voice.commands.CreateManagedVoiceCommand;
import com.universe.novel.application.voice.commands.UpdateManagedVoiceMetadataCommand;
import com.universe.novel.application.voice.dto.ManagedVoiceDTO;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedVoiceUseCasesTest {

    private static final UUID VOICE_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-05T11:00:00Z");

    @Mock
    private ManagedVoiceRepositoryPort repository;

    @Mock
    private IdGeneratorPort idGenerator;

    @Mock
    private ClockPort clock;

    private CreateManagedVoiceUseCase createUseCase;
    private UpdateManagedVoiceMetadataUseCase updateMetadataUseCase;
    private ChangeManagedVoiceProviderMappingUseCase changeMappingUseCase;
    private ActivateManagedVoiceUseCase activateUseCase;
    private DisableManagedVoiceUseCase disableUseCase;
    private SetDefaultManagedVoiceUseCase setDefaultUseCase;
    private ListManagedVoicesUseCase listUseCase;
    private GetManagedVoiceDetailUseCase getDetailUseCase;

    @BeforeEach
    void setUp() {
        createUseCase = new CreateManagedVoiceUseCase(repository, idGenerator, clock);
        updateMetadataUseCase = new UpdateManagedVoiceMetadataUseCase(repository, clock);
        changeMappingUseCase = new ChangeManagedVoiceProviderMappingUseCase(repository, clock);
        activateUseCase = new ActivateManagedVoiceUseCase(repository, clock);
        disableUseCase = new DisableManagedVoiceUseCase(repository, clock);
        setDefaultUseCase = new SetDefaultManagedVoiceUseCase(repository, clock);
        listUseCase = new ListManagedVoicesUseCase(repository);
        getDetailUseCase = new GetManagedVoiceDetailUseCase(repository);
    }

    @Test
    @DisplayName("Create first managed voice receives displayOrder 1 automatically")
    void shouldAssignDisplayOrder1ForFirstVoice() {
        when(repository.existsByVoiceKey("kiemlai-male-north-01")).thenReturn(false);
        when(repository.findMaxDisplayOrder()).thenReturn(0);
        when(idGenerator.generate()).thenReturn(VOICE_ID_1);
        when(clock.now()).thenReturn(T0);
        when(repository.save(any(ManagedVoice.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateManagedVoiceCommand command = new CreateManagedVoiceCommand(
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                false
        );

        ManagedVoiceDTO result = createUseCase.execute(command);

        assertThat(result.id()).isEqualTo(VOICE_ID_1);
        assertThat(result.voiceKey()).isEqualTo("kiemlai-male-north-01");
        assertThat(result.displayName()).isEqualTo("Anh Khôi");
        assertThat(result.providerVoiceId()).isEqualTo("minh-duc");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.displayOrder()).isEqualTo(1);
        assertThat(result.defaultVoice()).isFalse();
        assertThat(result.synthesisRevision()).isEqualTo(1L);

        verify(repository).save(any(ManagedVoice.class));
    }

    @Test
    @DisplayName("Create next managed voice receives max + 1 displayOrder automatically")
    void shouldAssignMaxPlusOneDisplayOrderForNextVoice() {
        when(repository.existsByVoiceKey("kiemlai-female-north-01")).thenReturn(false);
        when(repository.findMaxDisplayOrder()).thenReturn(5);
        when(idGenerator.generate()).thenReturn(VOICE_ID_2);
        when(clock.now()).thenReturn(T0);
        when(repository.save(any(ManagedVoice.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateManagedVoiceCommand command = new CreateManagedVoiceCommand(
                "kiemlai-female-north-01",
                "Mai Anh",
                "mai-anh",
                false
        );

        ManagedVoiceDTO result = createUseCase.execute(command);

        assertThat(result.displayOrder()).isEqualTo(6);
    }

    @Test
    @DisplayName("Reject duplicate voiceKey on creation")
    void shouldRejectDuplicateVoiceKey() {
        when(repository.existsByVoiceKey("kiemlai-male-north-01")).thenReturn(true);

        CreateManagedVoiceCommand command = new CreateManagedVoiceCommand(
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                false
        );

        assertThatThrownBy(() -> createUseCase.execute(command))
                .isInstanceOf(ManagedVoiceKeyAlreadyExistsException.class)
                .hasMessageContaining("kiemlai-male-north-01");

        verify(repository, never()).save(any(ManagedVoice.class));
    }

    @Test
    @DisplayName("Unmark previous default when creating a new default voice")
    void shouldUnmarkPreviousDefaultWhenCreatingNewDefault() {
        ManagedVoice existingDefault = ManagedVoice.create(
                VOICE_ID_1,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                true,
                T0
        );

        when(repository.existsByVoiceKey("kiemlai-female-north-01")).thenReturn(false);
        when(repository.findDefaultVoice()).thenReturn(Optional.of(existingDefault));
        when(repository.findMaxDisplayOrder()).thenReturn(1);
        when(idGenerator.generate()).thenReturn(VOICE_ID_2);
        when(clock.now()).thenReturn(T1);
        when(repository.save(any(ManagedVoice.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateManagedVoiceCommand command = new CreateManagedVoiceCommand(
                "kiemlai-female-north-01",
                "Mai Anh",
                "mai-anh",
                true
        );

        ManagedVoiceDTO result = createUseCase.execute(command);

        assertThat(result.id()).isEqualTo(VOICE_ID_2);
        assertThat(result.defaultVoice()).isTrue();
        assertThat(result.displayOrder()).isEqualTo(2);
        assertThat(existingDefault.isDefaultVoice()).isFalse();

        verify(repository).save(existingDefault);
    }

    @Test
    @DisplayName("Update display metadata without altering displayOrder")
    void shouldUpdateDisplayMetadata() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID_1,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        when(repository.findById(VOICE_ID_1)).thenReturn(Optional.of(voice));
        when(clock.now()).thenReturn(T1);
        when(repository.save(any(ManagedVoice.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateManagedVoiceMetadataCommand command = new UpdateManagedVoiceMetadataCommand(
                VOICE_ID_1,
                "Anh Khôi (Truyền Cảm)"
        );

        ManagedVoiceDTO result = updateMetadataUseCase.execute(command);

        assertThat(result.displayName()).isEqualTo("Anh Khôi (Truyền Cảm)");
        assertThat(result.displayOrder()).isEqualTo(1);
        assertThat(result.synthesisRevision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Change provider mapping increments synthesisRevision")
    void shouldChangeProviderMapping() {
        ManagedVoice voice = ManagedVoice.create(
                VOICE_ID_1,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                false,
                T0
        );

        when(repository.findById(VOICE_ID_1)).thenReturn(Optional.of(voice));
        when(clock.now()).thenReturn(T1);
        when(repository.save(any(ManagedVoice.class))).thenAnswer(inv -> inv.getArgument(0));

        ChangeManagedVoiceProviderMappingCommand command = new ChangeManagedVoiceProviderMappingCommand(
                VOICE_ID_1,
                "pham-tuyen"
        );

        ManagedVoiceDTO result = changeMappingUseCase.execute(command);

        assertThat(result.providerVoiceId()).isEqualTo("pham-tuyen");
        assertThat(result.synthesisRevision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Setting a new default voice removes previous default and marks new default")
    void shouldSetDefaultVoiceAndUnmarkPreviousDefault() {
        ManagedVoice currentDefault = ManagedVoice.create(
                VOICE_ID_1,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                true,
                T0
        );

        ManagedVoice target = ManagedVoice.create(
                VOICE_ID_2,
                "kiemlai-female-north-01",
                "Mai Anh",
                "mai-anh",
                2,
                false,
                T0
        );

        when(repository.findById(VOICE_ID_2)).thenReturn(Optional.of(target));
        when(repository.findDefaultVoice()).thenReturn(Optional.of(currentDefault));
        when(clock.now()).thenReturn(T1);
        when(repository.save(any(ManagedVoice.class))).thenAnswer(inv -> inv.getArgument(0));

        ManagedVoiceDTO result = setDefaultUseCase.execute(VOICE_ID_2);

        assertThat(result.id()).isEqualTo(VOICE_ID_2);
        assertThat(result.defaultVoice()).isTrue();
        assertThat(currentDefault.isDefaultVoice()).isFalse();

        verify(repository).save(currentDefault);
        verify(repository).save(target);
    }

    @Test
    @DisplayName("Reject setting disabled voice as default")
    void shouldRejectSettingDisabledVoiceAsDefault() {
        ManagedVoice disabledVoice = ManagedVoice.rehydrate(
                VOICE_ID_1,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                ManagedVoiceStatus.DISABLED,
                1,
                false,
                1L,
                T0,
                T0
        );

        when(repository.findById(VOICE_ID_1)).thenReturn(Optional.of(disabledVoice));

        assertThatThrownBy(() -> setDefaultUseCase.execute(VOICE_ID_1))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("Reject disabling default voice")
    void shouldRejectDisablingDefaultVoice() {
        ManagedVoice defaultVoice = ManagedVoice.create(
                VOICE_ID_1,
                "kiemlai-male-north-01",
                "Anh Khôi",
                "minh-duc",
                1,
                true,
                T0
        );

        when(repository.findById(VOICE_ID_1)).thenReturn(Optional.of(defaultVoice));

        assertThatThrownBy(() -> disableUseCase.execute(VOICE_ID_1))
                .isInstanceOf(ManagedVoiceInvalidStateException.class)
                .hasMessageContaining("mặc định");
    }

    @Test
    @DisplayName("List managed voices and get detail")
    void shouldListManagedVoicesAndGetDetail() {
        ManagedVoice voice1 = ManagedVoice.create(VOICE_ID_1, "voice-1", "V1", "p1", 1, false, T0);
        ManagedVoice voice2 = ManagedVoice.create(VOICE_ID_2, "voice-2", "V2", "p2", 2, true, T0);

        when(repository.findAll()).thenReturn(List.of(voice1, voice2));
        when(repository.findById(VOICE_ID_1)).thenReturn(Optional.of(voice1));

        List<ManagedVoiceDTO> list = listUseCase.execute();
        assertThat(list).hasSize(2);

        ManagedVoiceDTO detail = getDetailUseCase.execute(VOICE_ID_1);
        assertThat(detail.voiceKey()).isEqualTo("voice-1");
    }
}
