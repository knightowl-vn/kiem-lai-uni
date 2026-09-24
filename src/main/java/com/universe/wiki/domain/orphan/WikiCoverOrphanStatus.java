package com.universe.wiki.domain.orphan;

/**
 * Operational state machine for Wiki cover orphan reconciliation.
 *
 * <p>Locked state model:
 * <ul>
 *     <li>{@link #PENDING}: Observed as unreferenced, awaiting or released for grace-period processing.</li>
 *     <li>{@link #PROCESSING}: Claimed by a worker epoch under exclusive atomic lease.</li>
 * </ul>
 *
 * <p>No terminal failure state exists. Transient failures release back to {@link #PENDING}.
 */
public enum WikiCoverOrphanStatus {
    PENDING,
    PROCESSING
}
