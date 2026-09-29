package com.universe.community.domain;

import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.domain.exception.CommunityPostValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityPostTest {

    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MEDIA_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T10:00:00Z");

    @Nested
    @DisplayName("Creation Invariants")
    class CreationTests {

        @Test
        @DisplayName("Should create CommunityPost successfully with text-only caption")
        void shouldCreateTextOnlyPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID,
                    AUTHOR_ID,
                    "  Xin chào Kiếm Lai Universe!  ",
                    null,
                    CREATED_AT
            );

            assertThat(post.getId()).isEqualTo(POST_ID);
            assertThat(post.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(post.getCaption()).isEqualTo("Xin chào Kiếm Lai Universe!");
            assertThat(post.getImageMediaAssetId()).isNull();
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("Should create CommunityPost successfully with image attachment")
        void shouldCreatePostWithImage() {
            CommunityPost post = CommunityPost.create(
                    POST_ID,
                    AUTHOR_ID,
                    "Bức ảnh tuyệt đẹp",
                    MEDIA_ID,
                    CREATED_AT
            );

            assertThat(post.getId()).isEqualTo(POST_ID);
            assertThat(post.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(post.getCaption()).isEqualTo("Bức ảnh tuyệt đẹp");
            assertThat(post.getImageMediaAssetId()).isEqualTo(MEDIA_ID);
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("Should accept maximum 2000 characters caption")
        void shouldAcceptMaxCaptionLength() {
            String maxCaption = "a".repeat(2000);
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, maxCaption, null, CREATED_AT);

            assertThat(post.getCaption()).hasSize(2000);
        }

        @Test
        @DisplayName("Should reject null ID or author ID or createdAt")
        void shouldRejectNullMandatoryFields() {
            assertThatThrownBy(() -> CommunityPost.create(null, AUTHOR_ID, "caption", null, CREATED_AT))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, null, "caption", null, CREATED_AT))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null, empty or whitespace-only caption")
        void shouldRejectInvalidCaptions() {
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, null, null, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be null");

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "", null, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be blank");

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "   \n\t  ", null, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be blank");
        }

        @Test
        @DisplayName("Should reject caption exceeding 2000 characters")
        void shouldRejectExcessiveCaption() {
            String tooLongCaption = "a".repeat(2001);
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, tooLongCaption, null, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("exceeds maximum limit");
        }
    }

    @Nested
    @DisplayName("Caption Edit Invariants")
    class EditCaptionTests {

        @Test
        @DisplayName("Should increment contentVersion and update timestamp on effective edit")
        void shouldEditCaptionSuccessfully() {
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Initial caption", null, CREATED_AT);
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            boolean changed = post.editCaption(AUTHOR_ID, "Updated caption", editedAt);

            assertThat(changed).isTrue();
            assertThat(post.getCaption()).isEqualTo("Updated caption");
            assertThat(post.getContentVersion()).isEqualTo(1);
            assertThat(post.getUpdatedAt()).isEqualTo(editedAt);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("Should be an idempotent no-op when new caption matches current caption after trimming")
        void shouldBeNoOpWhenCaptionIsIdentical() {
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Same caption", null, CREATED_AT);
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            boolean changed = post.editCaption(AUTHOR_ID, "  Same caption  ", editedAt);

            assertThat(changed).isFalse();
            assertThat(post.getCaption()).isEqualTo("Same caption");
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getUpdatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("Should reject edit by non-author user")
        void shouldRejectEditByNonAuthor() {
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Initial caption", null, CREATED_AT);
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            assertThatThrownBy(() -> post.editCaption(OTHER_USER_ID, "Hacked caption", editedAt))
                    .isInstanceOf(CommunityPostUnauthorizedException.class)
                    .hasMessageContaining("not authorized");

            assertThatThrownBy(() -> post.editCaption(null, "Hacked caption", editedAt))
                    .isInstanceOf(CommunityPostUnauthorizedException.class);
        }

        @Test
        @DisplayName("Should reject blank new caption or caption exceeding 2000 chars")
        void shouldRejectInvalidNewCaption() {
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Initial caption", null, CREATED_AT);
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "", editedAt))
                    .isInstanceOf(CommunityPostValidationException.class);

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "   ", editedAt))
                    .isInstanceOf(CommunityPostValidationException.class);

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "a".repeat(2001), editedAt))
                    .isInstanceOf(CommunityPostValidationException.class);
        }

        @Test
        @DisplayName("Should reject edit timestamp before current updatedAt")
        void shouldRejectEditTimestampBeforeUpdatedAt() {
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Initial caption", null, CREATED_AT);
            Instant beforeCreatedAt = Instant.parse("2026-09-29T09:59:00Z");

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "New caption", beforeCreatedAt))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be before");
        }
    }

    @Nested
    @DisplayName("Rehydration Invariants")
    class RehydrationTests {

        @Test
        @DisplayName("Should rehydrate valid persisted post")
        void shouldRehydrateSuccessfully() {
            Instant updatedAt = Instant.parse("2026-09-29T10:15:00Z");
            CommunityPost post = CommunityPost.rehydrate(
                    POST_ID,
                    AUTHOR_ID,
                    "Rehydrated caption",
                    MEDIA_ID,
                    3,
                    CREATED_AT,
                    updatedAt
            );

            assertThat(post.getId()).isEqualTo(POST_ID);
            assertThat(post.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(post.getCaption()).isEqualTo("Rehydrated caption");
            assertThat(post.getImageMediaAssetId()).isEqualTo(MEDIA_ID);
            assertThat(post.getContentVersion()).isEqualTo(3);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(updatedAt);
        }

        @Test
        @DisplayName("Should reject negative contentVersion or updatedAt before createdAt")
        void shouldRejectInvalidRehydrationState() {
            assertThatThrownBy(() -> CommunityPost.rehydrate(
                    POST_ID, AUTHOR_ID, "caption", null, -1, CREATED_AT, CREATED_AT
            )).isInstanceOf(CommunityPostValidationException.class);

            Instant before = Instant.parse("2026-09-29T09:00:00Z");
            assertThatThrownBy(() -> CommunityPost.rehydrate(
                    POST_ID, AUTHOR_ID, "caption", null, 0, CREATED_AT, before
            )).isInstanceOf(CommunityPostValidationException.class);
        }
    }
}
