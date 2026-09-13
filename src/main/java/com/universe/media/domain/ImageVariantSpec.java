package com.universe.media.domain;

/**
 * Value Object representing a proportional max-width image variant specification.
 * <p>
 * Source of truth is the explicit {@code targetWidth}.
 * The {@code variantKey} is a deterministic canonical identity derived as {@code "w" + targetWidth}.
 */
public record ImageVariantSpec(
        int targetWidth
) {

    public static final int MIN_WIDTH = 16;
    public static final int MAX_WIDTH = 7680;

    public ImageVariantSpec {
        if (targetWidth < MIN_WIDTH || targetWidth > MAX_WIDTH) {
            throw new IllegalArgumentException(
                    "targetWidth must be between " + MIN_WIDTH + " and " + MAX_WIDTH + " pixels: " + targetWidth
            );
        }
    }

    public static ImageVariantSpec of(int targetWidth) {
        return new ImageVariantSpec(targetWidth);
    }

    /**
     * Returns the canonical variant key, e.g. "w300", "w800".
     */
    public String variantKey() {
        return "w" + targetWidth;
    }
}
