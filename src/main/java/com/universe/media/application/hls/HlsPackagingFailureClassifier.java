package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionContentHashMismatchException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StalePackagingLeaseException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.domain.PackagingFailureDisposition;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative, deterministic classifier mapping runtime exceptions from a claimed packaging
 * attempt onto {@link HlsPackagingOutcomeStatus} and {@link PackagingFailureDisposition}.
 *
 * <p>Generic {@code IOException} and {@code StorageException} failures are conservatively
 * classified as {@link PackagingFailureDisposition#TRANSIENT_RETRYABLE} so host or network blips
 * never permanently fail valid source media. Only conclusively non-recoverable source defects are
 * mapped to {@link PackagingFailureDisposition#PERMANENT_NON_RETRYABLE}.
 */
public final class HlsPackagingFailureClassifier {

    public static final int MAX_DIAGNOSTIC_LENGTH = 1000;

    public record Classification(
            HlsPackagingOutcomeStatus outcomeStatus,
            PackagingFailureDisposition disposition,
            Duration retryBackoff,
            String category,
            String errorReason
    ) {
    }

    private HlsPackagingFailureClassifier() {
    }

    public static Classification classify(
            Throwable failure,
            int attemptNumber,
            HlsPackagingArtifactLimits limits
    ) {
        Objects.requireNonNull(failure, "failure cannot be null.");
        Objects.requireNonNull(limits, "limits cannot be null.");

        Throwable matched;
        if ((matched = findInChain(failure, StalePackagingLeaseException.class)) != null) {
            return new Classification(
                    HlsPackagingOutcomeStatus.STALE_OR_CANCELLED,
                    null,
                    null,
                    "STALE_OR_CANCELLED",
                    formatReason(matched, failure)
            );
        }
        if ((matched = findInChain(failure, MediaAssetNotFoundException.class)) != null) {
            return new Classification(
                    HlsPackagingOutcomeStatus.STALE_OR_CANCELLED,
                    null,
                    null,
                    "ASSET_NOT_FOUND",
                    formatReason(matched, failure)
            );
        }

        if ((matched = findInChain(failure, HlsPackagingOperationalException.class)) != null) {
            return new Classification(
                    HlsPackagingOutcomeStatus.OPERATIONAL_BLOCKED,
                    PackagingFailureDisposition.TRANSIENT_RETRYABLE,
                    limits.maxRetryBackoff(),
                    "OPERATIONAL_BLOCKED",
                    formatReason(matched, failure)
            );
        }

        if ((matched = findInChain(failure, UploadContentMimeMismatchException.class)) != null) {
            return permanent("UNSUPPORTED_SOURCE_MIME", matched, failure);
        }
        if ((matched = findInChain(failure, UnsupportedHlsSourceMediaException.class)) != null) {
            return permanent("UNSUPPORTED_SOURCE_MEDIA", matched, failure);
        }
        if ((matched = findInChain(failure, MediaAssetVersionContentHashMismatchException.class)) != null) {
            return permanent("SOURCE_CONTENT_HASH_MISMATCH", matched, failure);
        }
        if ((matched = findInChain(failure, StorageObjectNotFoundException.class)) != null) {
            return permanent("SOURCE_OBJECT_NOT_FOUND", matched, failure);
        }
        if ((matched = findInChain(failure, MediaAssetVersionNotFoundException.class)) != null) {
            return permanent("SOURCE_VERSION_NOT_FOUND", matched, failure);
        }

        Duration backoff = computeExponentialBackoff(attemptNumber, limits.baseRetryBackoff(), limits.maxRetryBackoff());
        return new Classification(
                HlsPackagingOutcomeStatus.TRANSIENT_FAILURE,
                PackagingFailureDisposition.TRANSIENT_RETRYABLE,
                backoff,
                resolveTransientCategory(failure),
                formatReason(failure, failure)
        );
    }

    public static Duration computeExponentialBackoff(int attemptNumber, Duration baseBackoff, Duration maxBackoff) {
        int effectiveAttempt = Math.max(1, attemptNumber);
        int shift = Math.min(effectiveAttempt - 1, 10);
        long multiplier = 1L << shift;
        long baseMillis = Math.max(1L, baseBackoff.toMillis());
        long maxMillis = Math.max(baseMillis, maxBackoff.toMillis());
        long candidateMillis;
        try {
            candidateMillis = Math.multiplyExact(baseMillis, multiplier);
        } catch (ArithmeticException overflow) {
            candidateMillis = maxMillis;
        }
        return Duration.ofMillis(Math.min(candidateMillis, maxMillis));
    }

    private static Classification permanent(String category, Throwable matched, Throwable root) {
        return new Classification(
                HlsPackagingOutcomeStatus.PERMANENT_FAILURE,
                PackagingFailureDisposition.PERMANENT_NON_RETRYABLE,
                null,
                category,
                formatReason(matched, root)
        );
    }

    private static String resolveTransientCategory(Throwable failure) {
        if (findInChain(failure, HlsLocalPackagingException.class) != null) {
            return "LOCAL_PACKAGING_FAILURE";
        }
        return "TRANSIENT_STORAGE_OR_EXECUTION_FAILURE";
    }

    private static <T extends Throwable> T findInChain(Throwable root, Class<T> targetType) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && visited.size() < 32) {
            Throwable current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            if (targetType.isInstance(current)) {
                return targetType.cast(current);
            }
            if (current.getCause() != null) {
                queue.add(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null) {
                    queue.add(suppressed);
                }
            }
        }
        return null;
    }

    private static String formatReason(Throwable preferred, Throwable fallback) {
        String raw = preferred.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = fallback.getMessage();
        }
        if (raw == null || raw.isBlank()) {
            raw = preferred.getClass().getSimpleName();
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_DIAGNOSTIC_LENGTH
                ? trimmed.substring(0, MAX_DIAGNOSTIC_LENGTH)
                : trimmed;
    }
}
