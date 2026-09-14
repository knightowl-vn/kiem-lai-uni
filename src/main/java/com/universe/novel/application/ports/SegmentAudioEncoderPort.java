package com.universe.novel.application.ports;

import com.universe.novel.application.narration.SegmentAudioEncodingRequest;
import com.universe.novel.application.narration.SegmentAudioEncodingResult;

/**
 * Application boundary for encoding one normalized narration segment audio source.
 */
public interface SegmentAudioEncoderPort {

    /**
     * Encodes one segment audio source into one caller-owned temporary encoded resource.
     */
    SegmentAudioEncodingResult encode(SegmentAudioEncodingRequest request);
}
