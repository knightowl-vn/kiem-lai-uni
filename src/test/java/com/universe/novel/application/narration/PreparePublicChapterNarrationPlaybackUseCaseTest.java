package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.domain.narration.ManagedVoice;
import com.universe.novel.domain.narration.ManagedVoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreparePublicChapterNarrationPlaybackUseCase Unit Tests (MS-04.9H.9, H.9I5B)")
class PreparePublicChapterNarrationPlaybackUseCaseTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String VOICE_KEY = "hn-quynhanh";

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private ManagedVoiceRepositoryPort managedVoiceRepositoryPort;

    @Mock
    private ReaderChapterNarrationPreparationDispatcher dispatcher;

    private PreparePublicChapterNarrationPlaybackUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PreparePublicChapterNarrationPlaybackUseCase(
                readerChapterAccessQueryPort,
                managedVoiceRepositoryPort,
                dispatcher
        );
    }

    @Test
    @DisplayName("1. Published chapter access is checked BEFORE voice lookup occurs to prevent information disclosure")
    void publicationCheckOccursBeforeVoiceLookup() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new PreparePublicChapterNarrationPlaybackCommand(CHAPTER_ID, VOICE_KEY)))
                .isInstanceOf(ChapterNotFoundException.class);

        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("2. Hidden or unpublished chapter/volume throws ChapterNotFoundException and performs zero voice lookup")
    void hiddenChapterOrVolumeThrowsChapterNotFoundException() {
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, "any-voice-key"))
                .isInstanceOf(ChapterNotFoundException.class);

        verify(readerChapterAccessQueryPort).findPublishedById(CHAPTER_ID);
        verifyNoInteractions(managedVoiceRepositoryPort);
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("3. Exact trimmed active voiceKey resolves and dispatches, returning canonical voiceKey without exposing UUID")
    void exactTrimmedActiveVoiceKeyResolvesAndDispatches() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView));

        ManagedVoice activeVoice = ManagedVoice.create(
                VOICE_ID,
                VOICE_KEY,
                "Quỳnh Anh",
                "p-01",
                0,
                false,
                java.time.Instant.now()
        );
        when(managedVoiceRepositoryPort.findByVoiceKey(VOICE_KEY))
                .thenReturn(Optional.of(activeVoice));

        when(dispatcher.dispatch(any(ReaderChapterNarrationPreparationCommand.class)))
                .thenReturn(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);

        // Call with surrounding whitespace
        PreparePublicChapterNarrationPlaybackResult result = useCase.execute(
                new PreparePublicChapterNarrationPlaybackCommand(CHAPTER_ID, "  " + VOICE_KEY + "  ")
        );

        // Verification of ordering
        InOrder inOrder = inOrder(readerChapterAccessQueryPort, managedVoiceRepositoryPort, dispatcher);
        inOrder.verify(readerChapterAccessQueryPort).findPublishedById(CHAPTER_ID);
        inOrder.verify(managedVoiceRepositoryPort).findByVoiceKey(VOICE_KEY);
        inOrder.verify(dispatcher).dispatch(any(ReaderChapterNarrationPreparationCommand.class));

        // Verify dispatcher command args
        ArgumentCaptor<ReaderChapterNarrationPreparationCommand> cmdCaptor =
                ArgumentCaptor.forClass(ReaderChapterNarrationPreparationCommand.class);
        verify(dispatcher).dispatch(cmdCaptor.capture());
        ReaderChapterNarrationPreparationCommand dispatched = cmdCaptor.getValue();
        assertThat(dispatched.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(dispatched.managedVoiceId()).isEqualTo(VOICE_ID);

        // Verify public result contains canonical key and no UUID
        assertThat(result.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(result.voiceKey()).isEqualTo(VOICE_KEY);
        assertThat(result.dispatchStatus()).isEqualTo(ReaderChapterNarrationPreparationDispatchStatus.SCHEDULED);
    }

    @Test
    @DisplayName("4. Unknown voiceKey throws ManagedVoiceNotFoundException and does not dispatch")
    void unknownVoiceThrowsManagedVoiceNotFoundException() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView));
        when(managedVoiceRepositoryPort.findByVoiceKey("unknown-voice"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, "unknown-voice"))
                .isInstanceOf(ManagedVoiceNotFoundException.class);

        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("5. Inactive voice throws ManagedVoiceInvalidStateException and does not dispatch")
    void inactiveVoiceThrowsManagedVoiceInvalidStateException() {
        ReaderChapterAccessQueryPort.ReadableChapterReference accessView =
                new ReaderChapterAccessQueryPort.ReadableChapterReference(CHAPTER_ID, 1);
        when(readerChapterAccessQueryPort.findPublishedById(CHAPTER_ID))
                .thenReturn(Optional.of(accessView));

        ManagedVoice inactiveVoice = ManagedVoice.rehydrate(
                VOICE_ID,
                VOICE_KEY,
                "Quỳnh Anh",
                "p-01",
                ManagedVoiceStatus.DISABLED,
                0,
                false,
                1L,
                java.time.Instant.now(),
                java.time.Instant.now()
        );
        when(managedVoiceRepositoryPort.findByVoiceKey(VOICE_KEY))
                .thenReturn(Optional.of(inactiveVoice));

        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, VOICE_KEY))
                .isInstanceOf(ManagedVoiceInvalidStateException.class);

        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("6. Invalid input arguments throw IllegalArgumentException")
    void invalidInputArgumentsThrowIllegalArgumentException() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(null, VOICE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(CHAPTER_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
