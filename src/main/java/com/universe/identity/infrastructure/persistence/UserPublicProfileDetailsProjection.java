package com.universe.identity.infrastructure.persistence;

/**
 * Spring Data JPA projection for lightweight public user profile detail lookups including bio.
 */
public interface UserPublicProfileDetailsProjection {

    String getUserId();

    String getDisplayName();

    String getAvatarUrl();

    String getPublicHandle();

    String getBio();
}
