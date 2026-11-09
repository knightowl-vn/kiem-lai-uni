package com.universe.screen.domain;

/**
 * Three-state timeline inheritance and override policy for {@link ScreenEntry} Intro and Outro configuration.
 *
 * <ul>
 *   <li>{@link #INHERIT}: Use the parent {@link ScreenTitle}'s default timeline setting when present.</li>
 *   <li>{@link #OVERRIDE}: Use the episode-specific timeline setting configured on {@link ScreenEntry}.</li>
 *   <li>{@link #DISABLED}: Explicitly suppress the timeline feature for this episode even if the parent title defines a default.</li>
 * </ul>
 */
public enum ScreenTimelineMode {
    INHERIT,
    OVERRIDE,
    DISABLED;

    public boolean isInherit() {
        return this == INHERIT;
    }

    public boolean isOverride() {
        return this == OVERRIDE;
    }

    public boolean isDisabled() {
        return this == DISABLED;
    }
}
