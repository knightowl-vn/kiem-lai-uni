package com.universe.screen.domain;

import java.util.Objects;

/**
 * Pure domain compatibility policy governing which {@link ScreenEntryType}s
 * are permitted within a given {@link ScreenStructure}.
 *
 * <p>Locked product invariants:
 * <ul>
 *   <li>{@link ScreenStructure#SERIES} supports {@link ScreenEntryType#EPISODE}, {@link ScreenEntryType#SPECIAL}, and {@link ScreenEntryType#TRAILER}.</li>
 *   <li>{@link ScreenStructure#FEATURE} supports {@link ScreenEntryType#FEATURE}, {@link ScreenEntryType#SPECIAL}, and {@link ScreenEntryType#TRAILER}.</li>
 *   <li>A {@code SERIES} title must not contain a {@code FEATURE} entry.</li>
 *   <li>A {@code FEATURE} title must not contain an {@code EPISODE} entry (movies must not be modeled as fake "Episode 1").</li>
 * </ul>
 */
public final class ScreenEntryCompatibility {

    private ScreenEntryCompatibility() {
    }

    /**
     * Determines whether the given entry type is compatible with the specified title structure.
     *
     * @param structure title structure
     * @param entryType entry type
     * @return true if compatible, false otherwise
     * @throws NullPointerException if structure or entryType is null
     */
    public static boolean isCompatible(ScreenStructure structure, ScreenEntryType entryType) {
        Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        Objects.requireNonNull(entryType, "ScreenEntryType cannot be null.");

        return switch (structure) {
            case SERIES -> entryType == ScreenEntryType.EPISODE
                    || entryType == ScreenEntryType.SPECIAL
                    || entryType == ScreenEntryType.TRAILER;
            case FEATURE -> entryType == ScreenEntryType.FEATURE
                    || entryType == ScreenEntryType.SPECIAL
                    || entryType == ScreenEntryType.TRAILER;
        };
    }

    /**
     * Validates that the given entry type is compatible with the specified title structure.
     *
     * @param structure title structure
     * @param entryType entry type
     * @throws NullPointerException if structure or entryType is null
     * @throws IllegalArgumentException if incompatible
     */
    public static void validateCompatibility(ScreenStructure structure, ScreenEntryType entryType) {
        if (!isCompatible(structure, entryType)) {
            throw new IllegalArgumentException(
                    "ScreenEntryType " + entryType + " is incompatible with ScreenStructure " + structure + "."
            );
        }
    }
}
