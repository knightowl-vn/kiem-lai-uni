package com.universe.novel.application.narration;

import java.io.IOException;
import java.io.InputStream;

/**
 * Abstract stream source for one narration segment audio binary.
 */
@FunctionalInterface
public interface SegmentAudioBinarySource {

    /**
     * Opens a new readable stream for the segment audio binary.
     * <p>
     * The encoder owns and closes the returned stream.
     */
    InputStream openStream() throws IOException;
}
