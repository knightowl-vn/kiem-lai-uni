package com.universe.media.application.hls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration properties controlling opt-in automatic HLS packaging job creation after
 * committed Media VIDEO lifecycle events (MS-08 HLS2B3A3B).
 *
 * <p>Both toggles default to {@code false} in all ordinary environments so no background
 * HLS packaging work is queued unless explicitly enabled by an operator.
 */
@Component
public class HlsAutoEnqueueProperties {

    private final boolean enabled;
    private final boolean onRestore;

    @Autowired
    public HlsAutoEnqueueProperties(
            @Value("${media.hls.auto-enqueue.enabled:false}") boolean enabled,
            @Value("${media.hls.auto-enqueue.on-restore:false}") boolean onRestore
    ) {
        this.enabled = enabled;
        this.onRestore = onRestore;
    }

    public static HlsAutoEnqueueProperties disabled() {
        return new HlsAutoEnqueueProperties(false, false);
    }

    public static HlsAutoEnqueueProperties of(boolean enabled, boolean onRestore) {
        return new HlsAutoEnqueueProperties(enabled, onRestore);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isOnRestore() {
        return onRestore;
    }

    public boolean isRestoreAutoEnqueueActive() {
        return enabled && onRestore;
    }
}
