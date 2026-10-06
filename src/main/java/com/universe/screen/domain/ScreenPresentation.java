package com.universe.screen.domain;

/**
 * Visual presentation style of a Screen catalog title.
 *
 * <p>V1 supported presentation styles:
 * <ul>
 *   <li>{@link #ANIMATION_3D} — 3D animated visual presentation (donghua / CGI).</li>
 *   <li>{@link #LIVE_ACTION} — Live-action cinematography.</li>
 * </ul>
 */
public enum ScreenPresentation {
    /**
     * 3D animated visual presentation (donghua / CGI).
     */
    ANIMATION_3D,

    /**
     * Live-action cinematography.
     */
    LIVE_ACTION
}
