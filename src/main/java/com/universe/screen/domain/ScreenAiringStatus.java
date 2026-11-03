package com.universe.screen.domain;

/**
 * Airing / broadcast status of a ScreenTitle.
 *
 * <p>Orthogonal to {@link ScreenPublicationStatus} (which represents publication lifecycle in the catalog):
 * <ul>
 *   <li>{@link #UPCOMING} — Sắp chiếu (SERIES / FEATURE)</li>
 *   <li>{@link #ONGOING} — Đang chiếu (SERIES only)</li>
 *   <li>{@link #COMPLETED} — Trọn bộ (SERIES only)</li>
 *   <li>{@link #RELEASED} — Đã phát hành (FEATURE only)</li>
 * </ul>
 */
public enum ScreenAiringStatus {
    UPCOMING("Sắp chiếu"),
    ONGOING("Đang chiếu"),
    COMPLETED("Trọn bộ"),
    RELEASED("Đã phát hành");

    private final String displayName;

    ScreenAiringStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Validates whether this airing status is compatible with the given screen structure.
     */
    public boolean isCompatibleWith(ScreenStructure structure) {
        if (structure == null) {
            return false;
        }
        return switch (this) {
            case UPCOMING -> true;
            case ONGOING, COMPLETED -> structure == ScreenStructure.SERIES;
            case RELEASED -> structure == ScreenStructure.FEATURE;
        };
    }
}
