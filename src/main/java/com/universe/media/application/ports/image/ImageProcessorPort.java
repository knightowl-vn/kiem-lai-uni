package com.universe.media.application.ports.image;

import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MimeType;

import java.io.InputStream;

/**
 * Port for processing and resizing digital images into derivative variant resources.
 * <p>
 * Core Contract Semantics:
 * <ul>
 *     <li>Caller owns the passed {@code sourceStream} and is responsible for closing it.</li>
 *     <li>Caller owns the returned {@link ProcessedImageResource} and is responsible for closing it.</li>
 *     <li>Temporary resources created during processing must be cleaned up on failure.</li>
 *     <li>No framework, filesystem, or graphics library abstractions leak through this port.</li>
 * </ul>
 */
public interface ImageProcessorPort {

    /**
     * Processes and proportionally resizes the source image stream according to the variant specification.
     *
     * @param sourceStream   the readable source image binary stream (caller-owned)
     * @param sourceMimeType the declared MIME type of the source image
     * @param spec           the target image variant specification
     * @return a caller-owned {@link ProcessedImageResource} containing processed metadata and content
     */
    ProcessedImageResource process(
            InputStream sourceStream,
            MimeType sourceMimeType,
            ImageVariantSpec spec
    );
}
