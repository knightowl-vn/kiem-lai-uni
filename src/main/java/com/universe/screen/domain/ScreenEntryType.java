package com.universe.screen.domain;

/**
 * Categorization of a playable audiovisual content item ({@link ScreenEntry}).
 *
 * <p>V1 supported entry types:
 * <ul>
 *   <li>{@link #EPISODE} — Normal numbered playable installment of an episodic series.</li>
 *   <li>{@link #FEATURE} — Primary full-length or theatrical playable content of a feature title.</li>
 *   <li>{@link #SPECIAL} — Additional, standalone, or special audiovisual content (e.g. OVA, side story).</li>
 *   <li>{@link #TRAILER} — Promotional video or preview.</li>
 * </ul>
 */
public enum ScreenEntryType {

    /**
     * Normal numbered playable installment of an episodic series.
     */
    EPISODE,

    /**
     * Primary full-length or theatrical playable content of a feature title.
     */
    FEATURE,

    /**
     * Additional, standalone, or special audiovisual content.
     */
    SPECIAL,

    /**
     * Promotional video or preview.
     */
    TRAILER;

    /**
     * Checks whether this entry type is compatible with the given title structure.
     *
     * @param structure title structure
     * @return true if compatible, false otherwise
     */
    public boolean isCompatibleWith(ScreenStructure structure) {
        return ScreenEntryCompatibility.isCompatible(structure, this);
    }
}
