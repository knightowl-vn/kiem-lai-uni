package com.universe.media.domain;

/**
 * Status lifecycle of a storage key allocation.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link #RESERVED}: Initial reservation holding exclusivity before or during upload.</li>
 *   <li>{@link #COMMITTED}: Permanent stored object referenced by a registered MediaAssetVersion or MediaImageVariant.</li>
 *   <li>{@link #TOMBSTONE}: Terminal state for cancelled, aborted or deleted objects. Key can NEVER be re-used.</li>
 * </ul>
 */
public enum StorageKeyAllocationStatus {
    RESERVED,
    COMMITTED,
    TOMBSTONE
}
