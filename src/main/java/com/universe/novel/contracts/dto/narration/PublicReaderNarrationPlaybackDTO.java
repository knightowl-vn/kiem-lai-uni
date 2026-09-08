package com.universe.novel.contracts.dto.narration;

import com.universe.media.contracts.support.MediaDeliveryUrlSupport;
import com.universe.novel.application.narration.PreparePublicReaderNarrationPlaybackResult;
import com.universe.novel.application.narration.PrepareReaderNarrationPlaybackResult;

import java.util.UUID;

/**
 * Public, playback-safe response DTO for on-demand reader narration preparation (MS-04.9H.7D1A).
 *
 * @param chapterId                  identity of the chapter
 * @param segmentId                  identity of the narration segment
 * @param segmentIndex               zero-based segment sequence index
 * @param voiceKey                   public key of the managed voice
 * @param playableNow                whether the audio is ready and playable immediately
 * @param blocksPlayback             whether playback is blocked (true if not playable)
 * @param outcome                    orchestration outcome name
 * @param healthStatus               audio health status name (READY, OUTDATED, MISSING, FAILED)
 * @param refreshRecommended         whether background refresh/regeneration is recommended (true for OUTDATED)
 * @param continuationDispatchStatus status of the background continuation dispatch
 * @param audioUrl                   browser-safe streaming audio URL (null if not playable)
 */
public record PublicReaderNarrationPlaybackDTO(
        UUID chapterId,
        UUID segmentId,
        int segmentIndex,
        String voiceKey,
        boolean playableNow,
        boolean blocksPlayback,
        String outcome,
        String healthStatus,
        boolean refreshRecommended,
        String continuationDispatchStatus,
        String audioUrl
) {
    /**
     * Factory method mapping a public playback preparation result into a safe public DTO.
     *
     * @param publicResult public playback preparation result from the use case
     * @return public playback DTO, or {@code null} if publicResult is null
     */
    public static PublicReaderNarrationPlaybackDTO from(PreparePublicReaderNarrationPlaybackResult publicResult) {
        if (publicResult == null) {
            return null;
        }
        return from(publicResult.playbackResult(), publicResult.voiceKey());
    }

    /**
     * Factory method mapping an application {@link PrepareReaderNarrationPlaybackResult} and public voiceKey into a safe public DTO.
     *
     * @param result   composite playback preparation result from the use case
     * @param voiceKey public key of the managed voice
     * @return public playback DTO, or {@code null} if result is null
     */
    public static PublicReaderNarrationPlaybackDTO from(PrepareReaderNarrationPlaybackResult result, String voiceKey) {
        if (result == null) {
            return null;
        }
        var immediate = result.immediateResult();
        boolean playable = result.isPlayableNow();
        String audioUrl = (playable && result.mediaAssetId() != null)
                ? MediaDeliveryUrlSupport.contentUrl(result.mediaAssetId())
                : null;
        String healthStatus = immediate.finalHealth() != null
                ? immediate.finalHealth().name()
                : immediate.initialHealth().name();

        return new PublicReaderNarrationPlaybackDTO(
                immediate.chapterId(),
                immediate.segmentId(),
                immediate.segmentIndex(),
                voiceKey,
                playable,
                result.blocksPlayback(),
                immediate.outcome().name(),
                healthStatus,
                immediate.refreshRecommended(),
                result.continuationDispatchStatus().name(),
                audioUrl
        );
    }
}
