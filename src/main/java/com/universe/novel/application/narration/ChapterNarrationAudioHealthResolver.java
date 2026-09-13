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
 *     <li><strong>Audio exists and A == R:</strong> {@code READY}, failure diagnostic suppressed (null).</li>
 *     <li><strong>Audio exists and A != R:</strong> {@code OUTDATED}, failure diagnostic relevant ONLY when {@code F == R} (null otherwise).</li>
 *     <li><strong>No audio and F == R:</strong> {@code FAILED}, failure diagnostic relevant.</li>
 *     <li><strong>No audio and F != R (or no failure):</strong> {@code MISSING}, failure diagnostic suppressed (null).</li>
 * </ul>
 * where A = audio generated synthesis revision, F = failure attempted synthesis revision, R = current voice synthesis revision.
 */
public final class ChapterNarrationAudioHealthResolver {

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
            if (audio.isCompatibleWith(currentVoiceRevision)) {
                // A == R: READY, compatible assignment supersedes any failure diagnostic
                return new ChapterNarrationAudioHealthResolution(ChapterNarrationAudioHealthStatus.READY, null);
            } else {
                // A != R: OUTDATED, existing audio is playable; failure is relevant ONLY if regeneration failed for current revision (F == R)
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
}
