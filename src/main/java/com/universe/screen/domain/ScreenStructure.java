package com.universe.screen.domain;

/**
 * High-level organizational structure of a Screen catalog title.
 *
 * <p>V1 supported structures:
 * <ul>
 *   <li>{@link #SERIES} — Primary playable content consists of episodes.</li>
 *   <li>{@link #FEATURE} — Independent full-length or theatrical production.</li>
 * </ul>
 */
public enum ScreenStructure {
    /**
     * Episodic series composed of sequential playable entries.
     */
    SERIES,

    /**
     * Independent full-length or theatrical production.
     */
    FEATURE
}
