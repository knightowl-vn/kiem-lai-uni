package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Exception thrown when no ChapterNarrationAudio assignment exists for a given segment and voice pair.
 */
public class ChapterNarrationAudioNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_CHAPTER_NARRATION_AUDIO_NOT_FOUND";

    public ChapterNarrationAudioNotFoundException(UUID segmentId, UUID managedVoiceId) {
        super(
                DEFAULT_ERROR_CODE,
                "Không tìm thấy audio phân đoạn cho segment: " + segmentId + " và voice: " + managedVoiceId
        );
    }
}
