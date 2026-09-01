package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaAssetTest {

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Instant T1 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final Instant T2 =
            Instant.parse("2026-09-01T11:00:00Z");

    private static final Instant T3 =
            Instant.parse("2026-09-01T12:00:00Z");

    @Nested
    @DisplayName("Initial Registration")
    class InitialRegistrationTests {

        @Test
        @DisplayName("registerInitial creates an ACTIVE asset with version 1")
        void shouldRegisterInitialAssetSuccessfully() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            );

            assertThat(asset.getId()).isEqualTo(ASSET_ID);
            assertThat(asset.getMediaType()).isEqualTo(MediaType.IMAGE);
            assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(asset.getCurrentVersionNumber()).isEqualTo(1);
            assertThat(asset.getCreatedAt()).isEqualTo(T1);
            assertThat(asset.getUpdatedAt()).isEqualTo(T1);
            assertThat(asset.isActive()).isTrue();
            assertThat(asset.isArchived()).isFalse();
            assertThat(asset.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("registerInitial rejects null arguments")
        void shouldRejectNullArgumentsOnRegisterInitial() {
            assertThatThrownBy(() -> MediaAsset.registerInitial(
                    null,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MediaAsset ID cannot be null");

            assertThatThrownBy(() -> MediaAsset.registerInitial(
                    ASSET_ID,
                    null,
                    MediaVisibility.PUBLIC,
                    T1
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MediaType cannot be null");

            assertThatThrownBy(() -> MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    null,
                    T1
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("MediaVisibility cannot be null");

            assertThatThrownBy(() -> MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    null
            )).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Timestamp cannot be null");
        }
    }

    @Nested
    @DisplayName("Version Registration")
    class VersionRegistrationTests {

        @Test
        @DisplayName("registerNextVersion increments version number monotonically on ACTIVE asset")
        void shouldIncrementVersionMonotonically() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            );

            int v2 = asset.registerNextVersion(T2);
            assertThat(v2).isEqualTo(2);
            assertThat(asset.getCurrentVersionNumber()).isEqualTo(2);
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);

            int v3 = asset.registerNextVersion(T3);
            assertThat(v3).isEqualTo(3);
            assertThat(asset.getCurrentVersionNumber()).isEqualTo(3);
            assertThat(asset.getUpdatedAt()).isEqualTo(T3);
        }

        @Test
        @DisplayName("registerNextVersion throws IllegalStateException when asset is ARCHIVED")
        void shouldRejectNewVersionWhenArchived() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            );
            asset.archive(T2);

            assertThatThrownBy(() -> asset.registerNextVersion(T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot register a new version for a media asset with status: ARCHIVED");
        }

        @Test
        @DisplayName("registerNextVersion throws IllegalStateException when asset is DELETED")
        void shouldRejectNewVersionWhenDeleted() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            );
            asset.markDeleted(T2);

            assertThatThrownBy(() -> asset.registerNextVersion(T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot register a new version for a media asset with status: DELETED");
        }
    }

    @Nested
    @DisplayName("Visibility Mutation")
    class VisibilityMutationTests {

        @Test
        @DisplayName("changeVisibility modifies visibility and updates timestamp")
        void shouldChangeVisibilitySuccessfully() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.AUDIO,
                    MediaVisibility.PRIVATE,
                    T1
            );

            asset.changeVisibility(MediaVisibility.PUBLIC, T2);

            assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);

            asset.changeVisibility(MediaVisibility.RESTRICTED, T3);

            assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.RESTRICTED);
            assertThat(asset.getUpdatedAt()).isEqualTo(T3);
        }

        @Test
        @DisplayName("changeVisibility with identical value does not mutate updatedAt")
        void shouldNotMutateTimestampWhenVisibilityIsUnchanged() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.AUDIO,
                    MediaVisibility.PUBLIC,
                    T1
            );

            asset.changeVisibility(MediaVisibility.PUBLIC, T2);

            assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
            assertThat(asset.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        @DisplayName("changeVisibility rejects null timestamp even on the same-visibility path")
        void shouldRejectNullTimestampEvenWhenVisibilityIsUnchanged() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.AUDIO,
                    MediaVisibility.PUBLIC,
                    T1
            );

            assertThatThrownBy(() -> asset.changeVisibility(MediaVisibility.PUBLIC, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Timestamp cannot be null");
        }

        @Test
        @DisplayName("changeVisibility throws IllegalStateException when asset is DELETED")
        void shouldRejectVisibilityChangeOnDeletedAsset() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.DOCUMENT,
                    MediaVisibility.PUBLIC,
                    T1
            );
            asset.markDeleted(T2);

            assertThatThrownBy(() -> asset.changeVisibility(MediaVisibility.PRIVATE, T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot change visibility on a DELETED media asset");
        }
    }

    @Nested
    @DisplayName("Lifecycle Transitions")
    class LifecycleTransitionTests {

        @Test
        @DisplayName("ACTIVE -> ARCHIVED -> ACTIVE cycle operates correctly")
        void shouldArchiveAndRestoreSuccessfully() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    T1
            );

            asset.archive(T2);
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
            assertThat(asset.isArchived()).isTrue();
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);

            asset.restoreToActive(T3);
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(asset.isActive()).isTrue();
            assertThat(asset.getUpdatedAt()).isEqualTo(T3);
        }

        @Test
        @DisplayName("archive throws IllegalStateException if already ARCHIVED")
        void shouldRejectArchiveWhenAlreadyArchived() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    T1
            );
            asset.archive(T2);

            assertThatThrownBy(() -> asset.archive(T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Media asset is already ARCHIVED");
        }

        @Test
        @DisplayName("restoreToActive throws IllegalStateException if already ACTIVE")
        void shouldRejectRestoreWhenAlreadyActive() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    T1
            );

            assertThatThrownBy(() -> asset.restoreToActive(T2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Media asset is already ACTIVE");
        }

        @Test
        @DisplayName("ACTIVE -> DELETED is terminal and rejects further mutations")
        void shouldTransitionActiveToDeleted() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            );

            asset.markDeleted(T2);
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            assertThat(asset.isDeleted()).isTrue();
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);

            assertThatThrownBy(() -> asset.markDeleted(T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Media asset is already DELETED");

            assertThatThrownBy(() -> asset.archive(T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot archive a DELETED media asset");

            assertThatThrownBy(() -> asset.restoreToActive(T3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot restore a DELETED media asset");
        }

        @Test
        @DisplayName("ARCHIVED -> DELETED is allowed and terminal")
        void shouldTransitionArchivedToDeleted() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T1
            );

            asset.archive(T2);
            asset.markDeleted(T3);

            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            assertThat(asset.isDeleted()).isTrue();
            assertThat(asset.getUpdatedAt()).isEqualTo(T3);
        }
    }

    @Nested
    @DisplayName("Rehydration")
    class RehydrationTests {

        @Test
        @DisplayName("rehydrate restores aggregate state faithfully without side effects")
        void shouldRehydrateFaithfully() {
            MediaAsset asset = MediaAsset.rehydrate(
                    ASSET_ID,
                    MediaType.DOCUMENT,
                    MediaVisibility.RESTRICTED,
                    MediaAssetStatus.ARCHIVED,
                    5,
                    T1,
                    T2
            );

            assertThat(asset.getId()).isEqualTo(ASSET_ID);
            assertThat(asset.getMediaType()).isEqualTo(MediaType.DOCUMENT);
            assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.RESTRICTED);
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
            assertThat(asset.getCurrentVersionNumber()).isEqualTo(5);
            assertThat(asset.getCreatedAt()).isEqualTo(T1);
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);
        }

        @Test
        @DisplayName("rehydrate rejects invalid version number (< 1)")
        void shouldRejectInvalidVersionNumberOnRehydrate() {
            assertThatThrownBy(() -> MediaAsset.rehydrate(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.ACTIVE,
                    0,
                    T1,
                    T1
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current version number must be greater than or equal to 1");
        }

        @Test
        @DisplayName("rehydrate rejects updatedAt before createdAt")
        void shouldRejectUpdatedAtBeforeCreatedAtOnRehydrate() {
            assertThatThrownBy(() -> MediaAsset.rehydrate(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    MediaAssetStatus.ACTIVE,
                    1,
                    T2,
                    T1
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("updatedAt cannot be before createdAt");
        }
    }

    @Nested
    @DisplayName("Temporal Invariants")
    class TemporalInvariantTests {

        @Test
        @DisplayName("registerNextVersion rejects timestamp earlier than current updatedAt")
        void shouldRejectBackwardTimestampOnRegisterNextVersion() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T2
            );

            assertThatThrownBy(() -> asset.registerNextVersion(T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mutation timestamp cannot be before the last updated timestamp");
        }

        @Test
        @DisplayName("registerNextVersion succeeds with equal or later timestamp")
        void shouldAcceptEqualOrLaterTimestampOnRegisterNextVersion() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.IMAGE,
                    MediaVisibility.PUBLIC,
                    T2
            );

            // Equal timestamp
            int v2 = asset.registerNextVersion(T2);
            assertThat(v2).isEqualTo(2);
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);

            // Later timestamp
            int v3 = asset.registerNextVersion(T3);
            assertThat(v3).isEqualTo(3);
            assertThat(asset.getUpdatedAt()).isEqualTo(T3);
        }

        @Test
        @DisplayName("archive and restoreToActive reject backward timestamps")
        void shouldRejectBackwardTimestampOnArchiveAndRestore() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.VIDEO,
                    MediaVisibility.PUBLIC,
                    T2
            );

            assertThatThrownBy(() -> asset.archive(T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mutation timestamp cannot be before the last updated timestamp");

            asset.archive(T2);

            assertThatThrownBy(() -> asset.restoreToActive(T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mutation timestamp cannot be before the last updated timestamp");
        }

        @Test
        @DisplayName("markDeleted rejects backward timestamp")
        void shouldRejectBackwardTimestampOnMarkDeleted() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.DOCUMENT,
                    MediaVisibility.PUBLIC,
                    T2
            );

            assertThatThrownBy(() -> asset.markDeleted(T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mutation timestamp cannot be before the last updated timestamp");
        }

        @Test
        @DisplayName("changeVisibility with changed value rejects backward timestamp")
        void shouldRejectBackwardTimestampOnVisibilityChange() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.AUDIO,
                    MediaVisibility.PUBLIC,
                    T2
            );

            assertThatThrownBy(() -> asset.changeVisibility(MediaVisibility.PRIVATE, T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mutation timestamp cannot be before the last updated timestamp");
        }

        @Test
        @DisplayName("changeVisibility with unchanged value is no-op and does not reject backward timestamp")
        void shouldNotRejectTimestampWhenVisibilityIsUnchanged() {
            MediaAsset asset = MediaAsset.registerInitial(
                    ASSET_ID,
                    MediaType.AUDIO,
                    MediaVisibility.PUBLIC,
                    T2
            );

            // Same visibility with older timestamp should safely no-op without error
            asset.changeVisibility(MediaVisibility.PUBLIC, T1);

            assertThat(asset.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
            assertThat(asset.getUpdatedAt()).isEqualTo(T2);
        }
    }
}
