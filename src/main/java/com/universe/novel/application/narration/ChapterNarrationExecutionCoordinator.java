package com.universe.novel.application.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared JVM-local single-flight coordinator for whole-chapter narration work (H.10A1).
 * <p>
 * Ensures that at most one whole-chapter execution (Admin- or Reader-initiated) is active
 * or queued for any given {@code (chapterId, managedVoiceId)} pair.
 * <p>
 * <strong>Invariants:</strong>
 * <ul>
 *     <li>Thread-safe, atomic acquisition via {@link ConcurrentMap#putIfAbsent}.</li>
 *     <li>Rejects null arguments with {@link NullPointerException}.</li>
 *     <li>JVM-local only; no knowledge of Admin or Reader UI concepts.</li>
 *     <li>No worker execution logic, database, Media, TTS, or FFmpeg dependencies.</li>
 * </ul>
 */
@Component
public class ChapterNarrationExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChapterNarrationExecutionCoordinator.class);

    private record OperationKey(UUID chapterId, UUID managedVoiceId) {
        private OperationKey {
            Objects.requireNonNull(chapterId, "chapterId must not be null");
            Objects.requireNonNull(managedVoiceId, "managedVoiceId must not be null");
        }
    }

    private final ConcurrentMap<OperationKey, Boolean> inFlightKeys = new ConcurrentHashMap<>();

    /**
     * Attempts to atomically acquire single-flight ownership for the given chapter and voice.
     *
     * @param chapterId      identity of the chapter (non-null)
     * @param managedVoiceId identity of the managed voice (non-null)
     * @return {@code true} if ownership was successfully acquired; {@code false} if already in-flight
     */
    public boolean tryAcquire(UUID chapterId, UUID managedVoiceId) {
        OperationKey key = new OperationKey(chapterId, managedVoiceId);
        boolean acquired = inFlightKeys.putIfAbsent(key, Boolean.TRUE) == null;
        if (acquired) {
            log.debug("Acquired chapter narration execution ownership for chapter [{}] and voice [{}]",
                    chapterId, managedVoiceId);
        } else {
            log.debug("Chapter narration execution for chapter [{}] and voice [{}] is already in-flight",
                    chapterId, managedVoiceId);
        }
        return acquired;
    }

    /**
     * Releases single-flight ownership for the given chapter and voice.
     *
     * @param chapterId      identity of the chapter (non-null)
     * @param managedVoiceId identity of the managed voice (non-null)
     */
    public void release(UUID chapterId, UUID managedVoiceId) {
        OperationKey key = new OperationKey(chapterId, managedVoiceId);
        if (inFlightKeys.remove(key) != null) {
            log.debug("Released chapter narration execution ownership for chapter [{}] and voice [{}]",
                    chapterId, managedVoiceId);
        }
    }

    /**
     * Checks if a chapter narration execution is actively in-flight for the given chapter and voice.
     *
     * @param chapterId      identity of the chapter (non-null)
     * @param managedVoiceId identity of the managed voice (non-null)
     * @return {@code true} if actively in-flight; {@code false} otherwise
     */
    public boolean isInFlight(UUID chapterId, UUID managedVoiceId) {
        OperationKey key = new OperationKey(chapterId, managedVoiceId);
        return inFlightKeys.containsKey(key);
    }

    /**
     * Resets in-memory state for testing.
     */
    void clearForTesting() {
        inFlightKeys.clear();
    }
}
