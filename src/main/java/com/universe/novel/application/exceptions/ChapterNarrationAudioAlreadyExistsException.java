package com.universe.novel.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Thrown when attempting to persist a duplicate narration audio assignment for the same segment and managed voice.
 */
public class ChapterNarrationAudioAlreadyExistsException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ERROR_CODE = "NOVEL_CHAPTER_NARRATION_AUDIO_ALREADY_EXISTS";

    public ChapterNarrationAudioAlreadyExistsException(UUID segmentId, UUID managedVoiceId) {
        super(DEFAULT_ERROR_CODE, String.format(
                "Audio assignment already exists for segment '%s' and managed voice '%s'.",
                segmentId, managedVoiceId
        ));
    }

    public ChapterNarrationAudioAlreadyExistsException(String message) {
        super(DEFAULT_ERROR_CODE, message);
    }
}
