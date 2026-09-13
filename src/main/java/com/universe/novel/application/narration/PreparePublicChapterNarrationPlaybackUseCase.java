package com.universe.novel.application.narration;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ManagedVoiceInvalidStateException;
import com.universe.novel.application.exceptions.ManagedVoiceNotFoundException;
import com.universe.novel.application.ports.ManagedVoiceRepositoryPort;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
import com.universe.novel.domain.narration.ManagedVoice;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Public application use case enforcing published Reader visibility before resolving public voice keys,
 * then dispatching asynchronous chapter-level narration preparation (MS-04.9H.9, H.9I5B).
 */
@Service
public class PreparePublicChapterNarrationPlaybackUseCase {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final ReaderChapterNarrationPreparationDispatcher dispatcher;

    public PreparePublicChapterNarrationPlaybackUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            ReaderChapterNarrationPreparationDispatcher dispatcher
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort, "readerChapterAccessQueryPort must not be null"
        );
        this.managedVoiceRepositoryPort = Objects.requireNonNull(
                managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null"
        );
        this.dispatcher = Objects.requireNonNull(
                dispatcher, "dispatcher must not be null"
        );
    }

    public PreparePublicChapterNarrationPlaybackResult execute(PreparePublicChapterNarrationPlaybackCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.voiceKey());
    }

    public PreparePublicChapterNarrationPlaybackResult execute(UUID chapterId, String voiceKey) {
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (voiceKey == null || voiceKey.isBlank()) {
            throw new IllegalArgumentException("voiceKey must not be null or blank");
        }

        // 1. Enforce published Reader visibility BEFORE voice lookup to prevent information disclosure
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 2. Resolve public voiceKey
        String trimmedKey = voiceKey.trim();
        ManagedVoice voice = managedVoiceRepositoryPort.findByVoiceKey(trimmedKey)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(trimmedKey));

        // 3. Require voice ACTIVE
        if (!voice.isActive()) {
            throw new ManagedVoiceInvalidStateException("Managed voice is not active: " + trimmedKey);
        }

        // 4. Dispatch internally using chapterId + managedVoiceId
        ReaderChapterNarrationPreparationDispatchStatus dispatchStatus = dispatcher.dispatch(
                new ReaderChapterNarrationPreparationCommand(chapterId, voice.getId())
        );

        // 5. Return canonical persisted voiceKey and dispatch status without exposing internal voice UUID
        return new PreparePublicChapterNarrationPlaybackResult(
                chapterId,
                voice.getVoiceKey(),
                dispatchStatus
        );
    }
}
