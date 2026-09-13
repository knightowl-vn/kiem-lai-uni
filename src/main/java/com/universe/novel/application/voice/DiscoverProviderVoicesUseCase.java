package com.universe.novel.application.voice;

import com.universe.novel.application.narration.TtsProviderVoice;
import com.universe.novel.application.ports.TtsProviderPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Application use case for discovering available voices from the configured TTS provider.
 */
@Service
public class DiscoverProviderVoicesUseCase {

    private final TtsProviderPort ttsProviderPort;

    public DiscoverProviderVoicesUseCase(TtsProviderPort ttsProviderPort) {
        this.ttsProviderPort = Objects.requireNonNull(ttsProviderPort, "ttsProviderPort must not be null");
    }

    public List<TtsProviderVoice> execute() {
        return ttsProviderPort.listVoices();
    }
}
