package com.universe.media.application.ports.storage;

/**
 * Deterministic upload execution stages enforcing that binary media/init artifacts land
 * before media playlists, and {@code master.m3u8} lands strictly last.
 */
public enum HlsUploadStage {
    STAGE_1_INIT_AND_SEGMENTS(1),
    STAGE_2_MEDIA_PLAYLISTS(2),
    STAGE_3_MASTER_MANIFEST(3);

    private final int order;

    HlsUploadStage(int order) {
        this.order = order;
    }

    public int order() {
        return order;
    }
}
