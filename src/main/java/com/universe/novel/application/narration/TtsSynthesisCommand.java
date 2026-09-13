package com.universe.novel.application.narration;

/**
 * Passive command record for requesting text-to-speech audio synthesis from a provider.
 *
 * @param text            the text content to synthesize
 * @param providerVoiceId the external provider's voice identifier
 */
public record TtsSynthesisCommand(
        String text,
        String providerVoiceId
) {
}
