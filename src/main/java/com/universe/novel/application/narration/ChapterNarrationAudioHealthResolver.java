package com.universe.novel.application.narration;

import com.universe.novel.domain.narration.ChapterNarrationAudio;
import com.universe.novel.domain.narration.ChapterNarrationAudioFailure;

import java.util.Optional;

/**
 * Centralized, read-only application resolver for deriving {@link ChapterNarrationAudioHealthStatus}
 * and determining failure-diagnostic relevance according to MS-04.9H.8C2 locked health rules.
 * <p>
 * <strong>Decision Matrix:</strong>
 * <ul>
 *     <li><strong>Canonical Audio exists and A == R:</strong> {@code READY}, failure diagnostic suppressed (null).
 *         Canonical audio requires non-null contribution samples &gt; 0 and sample rate == 48,000 Hz.</li>
 *     <li><strong>Audio exists and (A != R or non-canonical timing):</strong> {@code OUTDATED}, failure diagnostic relevant ONLY when {@code F == R} (null otherwise).</li>
 *     <li><strong>No audio and F == R:</strong> {@code FAILED}, failure diagnostic relevant.</li>
 *     <li><strong>No audio and F != R (or no failure):</strong> {@code MISSING}, failure diagnostic suppressed (null).</li>
 * </ul>
 * where A = audio generated synthesis revision, F = failure attempted synthesis revision, R = current voice synthesis revision.
 */
public final class ChapterNarrationAudioHealthResolver {

    public static final int CANONICAL_SAMPLE_RATE_HZ = 48000;

    private ChapterNarrationAudioHealthResolver() {
    }

    /**
     * Resolves audio health status and relevant failure diagnostics for the given audio, failure, and voice revision.
     *
     * @param audio                persisted chapter narration audio assignment, or null if none
     * @param failure              persisted failure record, or null if none
     * @param currentVoiceRevision current synthesis revision of the managed voice
     * @return immutable health resolution
     */
    public static ChapterNarrationAudioHealthResolution resolve(
            ChapterNarrationAudio audio,
            ChapterNarrationAudioFailure failure,
            long currentVoiceRevision
    ) {
        if (audio != null) {
            if (isCanonicalReady(audio, currentVoiceRevision)) {
                // Compatible assignment with canonical timing supersedes any failure diagnostic
                return new ChapterNarrationAudioHealthResolution(ChapterNarrationAudioHealthStatus.READY, null);
            } else {
                // Audio is not canonical ready (outdated revision, legacy untimed, or non-canonical timing):
                // existing audio is playable; failure is relevant ONLY if regeneration failed for current revision (F == R)
                ChapterNarrationAudioFailure relevantFailure = (failure != null && failure.getAttemptedSynthesisRevision() == currentVoiceRevision)
                        ? failure
                        : null;
                return new ChapterNarrationAudioHealthResolution(ChapterNarrationAudioHealthStatus.OUTDATED, relevantFailure);
            }
        }

        // audio == null
        if (failure != null && failure.getAttemptedSynthesisRevision() == currentVoiceRevision) {
            // F == R: FAILED, failure occurred for the current voice revision
            return new ChapterNarrationAudioHealthResolution(ChapterNarrationAudioHealthStatus.FAILED, failure);
        }

        // No audio and no failure for current revision: MISSING, failures for older revisions suppressed
        return new ChapterNarrationAudioHealthResolution(ChapterNarrationAudioHealthStatus.MISSING, null);
    }

    /**
     * Resolves audio health status and relevant failure diagnostics using Optionals.
     *
     * @param audioOpt             optional audio assignment
     * @param failureOpt           optional failure record
     * @param currentVoiceRevision current synthesis revision of the managed voice
     * @return immutable health resolution
     */
    public static ChapterNarrationAudioHealthResolution resolveFromOptionals(
            Optional<ChapterNarrationAudio> audioOpt,
            Optional<ChapterNarrationAudioFailure> failureOpt,
            long currentVoiceRevision
    ) {
        return resolve(
                audioOpt != null ? audioOpt.orElse(null) : null,
                failureOpt != null ? failureOpt.orElse(null) : null,
                currentVoiceRevision
        );
    }

    static boolean isCanonicalReady(ChapterNarrationAudio audio, long currentVoiceRevision) {
        return audio != null
                && audio.isCompatibleWith(currentVoiceRevision)
                && audio.hasEncodedTiming()
                && audio.getEncodedContributionSamples() != null
                && audio.getEncodedContributionSamples() > 0
                && audio.getEncodedSampleRateHz() != null
                && audio.getEncodedSampleRateHz() == CANONICAL_SAMPLE_RATE_HZ;
    }
}
