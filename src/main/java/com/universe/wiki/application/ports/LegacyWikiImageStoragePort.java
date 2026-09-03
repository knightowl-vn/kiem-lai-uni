package com.universe.wiki.application.ports;

/**
 * Port dedicated strictly to legacy Cloudinary image deletion during orphan cleanup.
 *
 * All active image uploads and content delivery are handled exclusively via MediaContract.
 */
public interface LegacyWikiImageStoragePort {

    void delete(
            String publicId
    );
}
