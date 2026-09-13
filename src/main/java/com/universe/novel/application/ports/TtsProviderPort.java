package com.universe.novel.application.ports;

import com.universe.novel.application.narration.TtsProviderVoice;
import com.universe.novel.application.narration.TtsSynthesisCommand;
import com.universe.novel.application.narration.TtsSynthesisResult;

import java.util.List;

/**
 * Outbound port for text-to-speech (TTS) voice listing and audio synthesis.
 * <p>
 * Decouples Novel application logic from specific TTS providers (e.g. VieNeu).
 */
public interface TtsProviderPort {

    /**
     * Lists all available voices from the underlying TTS provider.
     *
     * @return a list of provider voices
     */
    List<TtsProviderVoice> listVoices();

    /**
     * Synthesizes audio using a structured {@link TtsSynthesisCommand}.
     *
     * @param command the synthesis command containing text and providerVoiceId
     * @return the synthesis result containing binary audio bytes and media type
     */
    TtsSynthesisResult synthesize(TtsSynthesisCommand command);
}
