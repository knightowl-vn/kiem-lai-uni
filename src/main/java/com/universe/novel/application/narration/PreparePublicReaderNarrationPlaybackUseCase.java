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
 * Public application use case that enforces published Reader visibility before resolving public voice keys,
 * preventing voice state disclosures on hidden/unpublished chapters (MS-04.9H.7D1A, MS-04.9H.7D1A1).
 */
@Service
public class PreparePublicReaderNarrationPlaybackUseCase {

    private final ReaderChapterAccessQueryPort readerChapterAccessQueryPort;
    private final ManagedVoiceRepositoryPort managedVoiceRepositoryPort;
    private final PrepareReaderNarrationPlaybackUseCase preparePlaybackUseCase;

    public PreparePublicReaderNarrationPlaybackUseCase(
            ReaderChapterAccessQueryPort readerChapterAccessQueryPort,
            ManagedVoiceRepositoryPort managedVoiceRepositoryPort,
            PrepareReaderNarrationPlaybackUseCase preparePlaybackUseCase
    ) {
        this.readerChapterAccessQueryPort = Objects.requireNonNull(
                readerChapterAccessQueryPort, "readerChapterAccessQueryPort must not be null"
        );
        this.managedVoiceRepositoryPort = Objects.requireNonNull(
                managedVoiceRepositoryPort, "managedVoiceRepositoryPort must not be null"
        );
        this.preparePlaybackUseCase = Objects.requireNonNull(
                preparePlaybackUseCase, "preparePlaybackUseCase must not be null"
        );
    }

    /**
     * Executes the public reader narration playback preparation workflow for the given command.
     *
     * @param command input command containing chapterId, segmentId, and voiceKey
     * @return composite public playback result
     */
    public PreparePublicReaderNarrationPlaybackResult execute(PreparePublicReaderNarrationPlaybackCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return execute(command.chapterId(), command.segmentId(), command.voiceKey());
    }

    /**
     * Executes the public reader narration playback preparation workflow for the given parameters.
     *
     * @param chapterId identity of the published chapter
     * @param segmentId identity of the requested segment
     * @param voiceKey  public key of the managed voice
     * @return composite public playback result
     */
    public PreparePublicReaderNarrationPlaybackResult execute(UUID chapterId, UUID segmentId, String voiceKey) {
        // 1. Validate input parameters
        if (chapterId == null) {
            throw new IllegalArgumentException("chapterId must not be null");
        }
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId must not be null");
        }
        if (voiceKey == null || voiceKey.isBlank()) {
            throw new IllegalArgumentException("voiceKey must not be null or blank");
        }

        // 2. Enforce published Reader visibility BEFORE voice lookup to prevent information disclosure
        readerChapterAccessQueryPort.findPublishedById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        // 3. Resolve public voiceKey
        String trimmedKey = voiceKey.trim();
        ManagedVoice voice = managedVoiceRepositoryPort.findByVoiceKey(trimmedKey)
                .orElseThrow(() -> new ManagedVoiceNotFoundException(trimmedKey));

        // 4. Require voice ACTIVE
        if (!voice.isActive()) {
            throw new ManagedVoiceInvalidStateException("Giọng đọc không khả dụng: " + trimmedKey);
        }

        // 5. Delegate to internal preparation facade
        PrepareReaderNarrationPlaybackResult playbackResult = preparePlaybackUseCase.execute(
                chapterId,
                segmentId,
                voice.getId()
        );

        // 6. Return canonical persisted voiceKey
        return new PreparePublicReaderNarrationPlaybackResult(playbackResult, voice.getVoiceKey());
    }
}
