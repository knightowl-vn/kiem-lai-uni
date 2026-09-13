package com.universe.novel.application.ports;

import com.universe.novel.application.narration.ChapterAudioEncodingRequest;
import com.universe.novel.application.narration.ChapterAudioEncodingResult;

/**
 * Application boundary for encoding assembled chapter narration audio.
 */
public interface ChapterAudioEncoderPort {

    /**
     * Encodes an assembly resource into one caller-owned temporary resource.
     */
    ChapterAudioEncodingResult encode(ChapterAudioEncodingRequest request);
}
