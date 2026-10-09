package com.universe.media.application.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Operational activation barrier for the Media Storage-Key Allocation Registry cutover (E2-S2B).
 *
 * <p>DEFAULT-OFF rollout safety gate.
 * When inactive (default {@code media.storage.registry.cutover-active=false}):
 * <ul>
 *   <li>Legacy writers operate without creating reservations in {@code media_storage_key_allocations};</li>
 *   <li>Legacy failure compensation operates without modifying allocation records;</li>
 *   <li>Legacy purge operates without allocation tombstoning;</li>
 *   <li>Registration endpoints do not mandate prior allocation ownership.</li>
 * </ul>
 *
 * <p>Enabling this cutover requires explicit configuration property {@code media.storage.registry.cutover-active=true},
 * which MUST ONLY be enabled after historical collision preflight, controlled backfill, delta reconciliation,
 * writer coverage verification, and human deployment approval have completed.
 */
@Component
public class MediaStorageCutoverBarrier {

    public static final String CUTOVER_ACTIVE_PROPERTY = "media.storage.registry.cutover-active";

    private final boolean cutoverActive;

    public MediaStorageCutoverBarrier(
            @Value("${media.storage.registry.cutover-active:false}") boolean cutoverActive
    ) {
        this.cutoverActive = cutoverActive;
    }

    /**
     * Indicates whether the storage-key allocation registry is authoritative for write paths.
     *
     * @return true if cutover is explicitly activated; false by default.
     */
    public boolean isCutoverActive() {
        return cutoverActive;
    }
}
