package com.universe.wiki.domain.orphan;

/**
 * Operational state machine for Wiki cover orphan reconciliation.
 *
 * <p>Locked state model:
 * <ul>
 *     <li>{@link #PENDING}: Observed as unreferenced, awaiting or released for grace-period processing.</li>
 *     <li>{@link #PROCESSING}: Claimed by a worker epoch under exclusive atomic lease.</li>
 *     <li>{@link #DELETING}: Durably fenced for Media platform deletion following zero-reference verification.</li>
 * </ul>
 *
 * <p>Transient failures in DELETING remain DELETING for at-least-once retry.
 */
public enum WikiCoverOrphanStatus {
    PENDING,
    PROCESSING,
    DELETING
}
