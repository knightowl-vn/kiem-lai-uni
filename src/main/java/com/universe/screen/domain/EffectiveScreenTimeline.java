package com.universe.screen.domain;

import java.util.Objects;

/**
 * Deterministic resolved playback timeline for a {@link ScreenEntry} combined with its parent {@link ScreenTitle}.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li><strong>Intro</strong>:
 *     <ul>
 *       <li>{@link ScreenTimelineMode#DISABLED}: no effective Intro ({@code null}).</li>
 *       <li>{@link ScreenTimelineMode#OVERRIDE}: uses the episode's validated Intro interval.</li>
 *       <li>{@link ScreenTimelineMode#INHERIT}: uses the parent title's default Intro interval when present, otherwise {@code null}.</li>
 *     </ul>
 *   </li>
 *   <li><strong>Outro</strong>:
 *     <ul>
 *       <li>{@link ScreenTimelineMode#DISABLED}: no effective Outro ({@code null}).</li>
 *       <li>{@link ScreenTimelineMode#OVERRIDE}: uses the episode's validated Outro marker.</li>
 *       <li>{@link ScreenTimelineMode#INHERIT}: uses the parent title's default Outro marker when present, otherwise {@code null}.</li>
 *     </ul>
 *   </li>
 *   <li><strong>Duration safety</strong>: If the entry has a known positive {@code durationSeconds}, any candidate
 *       Intro with {@code endSeconds > durationSeconds} (or {@code startSeconds >= durationSeconds}) is suppressed,
 *       and any candidate Outro with {@code startSeconds >= durationSeconds} is suppressed. The resolver never clamps
 *       or invents a seek target.</li>
 *   <li><strong>Overlap safety</strong>: If both candidate Intro and candidate Outro exist and
 *       {@code outroStartSeconds < introEndSeconds}, the conflicting Outro marker is suppressed ({@code null}) so
 *       Intro and Outro never overlap at runtime.</li>
 * </ul>
 */
public record EffectiveScreenTimeline(
        ScreenIntroInterval introInterval,
        ScreenOutroMarker outroMarker
) {

    public static final EffectiveScreenTimeline EMPTY = new EffectiveScreenTimeline(null, null);

    public static EffectiveScreenTimeline resolve(ScreenTitle title, ScreenEntry entry) {
        Objects.requireNonNull(entry, "ScreenEntry cannot be null.");

        ScreenIntroInterval candidateIntro = resolveCandidateIntro(title, entry);
        ScreenOutroMarker candidateOutro = resolveCandidateOutro(title, entry);

        Long durationSeconds = entry.getDurationSeconds();
        if (durationSeconds != null && durationSeconds > 0) {
            if (candidateIntro != null
                    && (candidateIntro.startSeconds() >= durationSeconds
                    || candidateIntro.endSeconds() > durationSeconds)) {
                candidateIntro = null;
            }
            if (candidateOutro != null) {
                if (candidateOutro.startSeconds() >= durationSeconds
                        || (candidateOutro.endSeconds() != null && candidateOutro.endSeconds() > durationSeconds)) {
                    candidateOutro = null;
                }
            }
        }

        if (candidateIntro != null
                && candidateOutro != null
                && candidateOutro.startSeconds() < candidateIntro.endSeconds()) {
            candidateOutro = null;
        }

        if (candidateIntro == null && candidateOutro == null) {
            return EMPTY;
        }
        return new EffectiveScreenTimeline(candidateIntro, candidateOutro);
    }

    /**
     * Strictly validates the combined effective timeline of an entry against its parent title and known duration
     * for Admin / command-time validation.
     *
     * @throws IllegalArgumentException if the resolved Intro and Outro overlap or exceed a known entry duration
     */
    public static void validateCombinedTimeline(ScreenTitle title, ScreenEntry entry) {
        Objects.requireNonNull(entry, "ScreenEntry cannot be null.");

        ScreenIntroInterval candidateIntro = resolveCandidateIntro(title, entry);
        ScreenOutroMarker candidateOutro = resolveCandidateOutro(title, entry);

        if (candidateIntro != null
                && candidateOutro != null
                && candidateOutro.startSeconds() < candidateIntro.endSeconds()) {
            throw new IllegalArgumentException(
                    "Effective outroStartSeconds (" + candidateOutro.startSeconds()
                            + ") cannot be before effective introEndSeconds (" + candidateIntro.endSeconds() + ")."
            );
        }

        Long durationSeconds = entry.getDurationSeconds();
        if (durationSeconds != null && durationSeconds > 0) {
            if (candidateIntro != null && candidateIntro.endSeconds() > durationSeconds) {
                throw new IllegalArgumentException(
                        "Effective introEndSeconds (" + candidateIntro.endSeconds()
                                + ") cannot exceed entry durationSeconds (" + durationSeconds + ")."
                );
            }
            if (candidateOutro != null) {
                if (candidateOutro.startSeconds() >= durationSeconds) {
                    throw new IllegalArgumentException(
                            "Effective outroStartSeconds (" + candidateOutro.startSeconds()
                                    + ") must be strictly less than entry durationSeconds (" + durationSeconds + ")."
                    );
                }
                if (candidateOutro.endSeconds() != null && candidateOutro.endSeconds() > durationSeconds) {
                    throw new IllegalArgumentException(
                            "Effective outroEndSeconds (" + candidateOutro.endSeconds()
                                    + ") cannot exceed entry durationSeconds (" + durationSeconds + ")."
                    );
                }
            }
        }
    }

    private static ScreenIntroInterval resolveCandidateIntro(ScreenTitle title, ScreenEntry entry) {
        return switch (entry.getIntroMode()) {
            case DISABLED -> null;
            case OVERRIDE -> entry.getIntroOverride();
            case INHERIT -> title != null ? title.getDefaultIntroInterval() : null;
        };
    }

    private static ScreenOutroMarker resolveCandidateOutro(ScreenTitle title, ScreenEntry entry) {
        return switch (entry.getOutroMode()) {
            case DISABLED -> null;
            case OVERRIDE -> entry.getOutroOverride();
            case INHERIT -> title != null ? title.getDefaultOutroMarker() : null;
        };
    }

    public boolean hasIntro() {
        return introInterval != null;
    }

    public boolean hasOutro() {
        return outroMarker != null;
    }

    public Long introStartSeconds() {
        return introInterval != null ? introInterval.startSeconds() : null;
    }

    public Long introEndSeconds() {
        return introInterval != null ? introInterval.endSeconds() : null;
    }

    public Long outroStartSeconds() {
        return outroMarker != null ? outroMarker.startSeconds() : null;
    }

    public Long outroEndSeconds() {
        return outroMarker != null ? outroMarker.endSeconds() : null;
    }
}
