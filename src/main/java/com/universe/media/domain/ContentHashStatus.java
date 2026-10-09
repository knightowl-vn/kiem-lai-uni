package com.universe.media.domain;

/**
 * Provenance classification of a media version's binary SHA-256 digest.
 *
 * <ul>
 *   <li>{@link #SERVER_COMPUTED}: Authoritatively computed by backend JVM streaming through DigestInputStream.</li>
 *   <li>{@link #CLIENT_DECLARED_UNVERIFIED}: Provided by client (e.g. browser incremental hasher) without independent backend re-verification.</li>
 * </ul>
 */
public enum ContentHashStatus {
    SERVER_COMPUTED,
    CLIENT_DECLARED_UNVERIFIED
}
