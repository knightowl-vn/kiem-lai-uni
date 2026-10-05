package com.universe.identity.infrastructure.persistence;

/**
 * Spring Data JPA projection for lightweight public user profile lookups.
 */
public interface UserPublicProfileProjection {

    String getUserId();

    String getDisplayName();

    String getAvatarUrl();

    String getPublicHandle();
}
