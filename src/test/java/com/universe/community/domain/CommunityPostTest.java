package com.universe.community.domain;

import com.universe.community.domain.exception.CommunityPostPendingEditConflictException;
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
                    CommunityPostStatus.PUBLISHED,
                    CREATED_AT,
                    CREATED_AT,
                    null
            );

            assertThat(post.getId()).isEqualTo(POST_ID);
            assertThat(post.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(post.getCaption()).isEqualTo("Xin chào Kiếm Lai Universe!");
            assertThat(post.getImageMediaAssetId()).isNull();
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("Should create CommunityPost successfully with image attachment")
        void shouldCreatePostWithImage() {
            CommunityPost post = CommunityPost.create(
                    POST_ID,
                    AUTHOR_ID,
                    "Bức ảnh tuyệt đẹp",
                    MEDIA_ID,
                    CommunityPostStatus.PUBLISHED,
                    CREATED_AT,
                    CREATED_AT,
                    null
            );

            assertThat(post.getId()).isEqualTo(POST_ID);
            assertThat(post.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(post.getCaption()).isEqualTo("Bức ảnh tuyệt đẹp");
            assertThat(post.getImageMediaAssetId()).isEqualTo(MEDIA_ID);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("Should create pending review post under pre-moderation")
        void shouldCreatePendingReviewPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID,
                    AUTHOR_ID,
                    "Bài viết chờ duyệt",
                    null,
                    CommunityPostStatus.PENDING_REVIEW,
                    CREATED_AT,
                    null,
                    CREATED_AT
            );

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PENDING_REVIEW);
            assertThat(post.getPublishedAt()).isNull();
            assertThat(post.getReviewRequestedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("Should accept maximum 2000 characters caption")
        void shouldAcceptMaxCaptionLength() {
            String maxCaption = "a".repeat(2000);
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, maxCaption, null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );

            assertThat(post.getCaption()).hasSize(2000);
        }

        @Test
        @DisplayName("Should reject null ID or author ID or status or createdAt")
        void shouldRejectNullMandatoryFields() {
            assertThatThrownBy(() -> CommunityPost.create(null, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, null, "caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, null, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, null, CREATED_AT, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null, empty or whitespace-only caption")
        void shouldRejectInvalidCaptions() {
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, null, null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be null");

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be blank");

            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "   \n\t  ", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be blank");
        }

        @Test
        @DisplayName("Should reject caption exceeding 2000 characters")
        void shouldRejectExcessiveCaption() {
            String tooLongCaption = "a".repeat(2001);
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, tooLongCaption, null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("exceeds maximum limit");
        }
    }

    @Nested
    @DisplayName("Publication Status Invariants")
    class StatusInvariantTests {

        @Test
        @DisplayName("PUBLISHED invariant: requires non-null publishedAt and null reviewRequestedAt")
        void shouldEnforcePublishedInvariants() {
            // Valid PUBLISHED
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);
            assertThat(post.getPublishedAt()).isNotNull();
            assertThat(post.getReviewRequestedAt()).isNull();

            // Invalid: publishedAt is null
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, null, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("PUBLISHED post must have a non-null publishedAt timestamp.");

            // Invalid: reviewRequestedAt is non-null
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("PUBLISHED post must have null reviewRequestedAt timestamp.");
        }

        @Test
        @DisplayName("PENDING_REVIEW invariant: requires non-null reviewRequestedAt; publishedAt can be null or non-null")
        void shouldEnforcePendingReviewInvariants() {
            // Valid new pending post (never published)
            CommunityPost newPending = CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, CREATED_AT);
            assertThat(newPending.getPublishedAt()).isNull();
            assertThat(newPending.getReviewRequestedAt()).isEqualTo(CREATED_AT);

            // Valid re-review pending post (was published before)
            CommunityPost reReview = CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PENDING_REVIEW, CREATED_AT, CREATED_AT.minusSeconds(100), CREATED_AT);
            assertThat(reReview.getPublishedAt()).isEqualTo(CREATED_AT.minusSeconds(100));
            assertThat(reReview.getReviewRequestedAt()).isEqualTo(CREATED_AT);

            // Invalid: reviewRequestedAt is null
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("PENDING_REVIEW post must have a non-null reviewRequestedAt timestamp.");
        }

        @Test
        @DisplayName("HIDDEN invariant: requires non-null publishedAt and null reviewRequestedAt")
        void shouldEnforceHiddenInvariants() {
            // Valid HIDDEN
            CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.HIDDEN, CREATED_AT, CREATED_AT, null);
            assertThat(post.getPublishedAt()).isNotNull();
            assertThat(post.getReviewRequestedAt()).isNull();

            // Invalid: publishedAt is null
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.HIDDEN, CREATED_AT, null, null))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("HIDDEN post must have a non-null publishedAt timestamp.");

            // Invalid: reviewRequestedAt is non-null
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.HIDDEN, CREATED_AT, CREATED_AT, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("HIDDEN post must have null reviewRequestedAt timestamp.");
        }

        @Test
        @DisplayName("REJECTED invariant: requires null reviewRequestedAt; publishedAt can be null or non-null")
        void shouldEnforceRejectedInvariants() {
            // Valid: never published
            CommunityPost initialRejected = CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.REJECTED, CREATED_AT, null, null);
            assertThat(initialRejected.getPublishedAt()).isNull();
            assertThat(initialRejected.getReviewRequestedAt()).isNull();

            // Valid: re-review rejected (preserves historical publishedAt)
            CommunityPost reReviewRejected = CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.REJECTED, CREATED_AT, CREATED_AT.minusSeconds(100), null);
            assertThat(reReviewRejected.getPublishedAt()).isEqualTo(CREATED_AT.minusSeconds(100));
            assertThat(reReviewRejected.getReviewRequestedAt()).isNull();

            // Invalid: reviewRequestedAt is non-null
            assertThatThrownBy(() -> CommunityPost.create(POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.REJECTED, CREATED_AT, null, CREATED_AT))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("REJECTED post must have null reviewRequestedAt timestamp.");
        }
    }

    @Nested
    @DisplayName("Caption Edit Invariants")
    class EditCaptionTests {

        @Test
        @DisplayName("Under AUTO_PUBLISH: edit preserves PUBLISHED status, preserves publishedAt, null reviewRequestedAt")
        void shouldEditCaptionSuccessfullyUnderAutoPublish() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            boolean changed = post.editCaption(AUTHOR_ID, "Updated caption", editedAt, CommunityPublicationMode.AUTO_PUBLISH);

            assertThat(changed).isTrue();
            assertThat(post.getCaption()).isEqualTo("Updated caption");
            assertThat(post.getContentVersion()).isEqualTo(1);
            assertThat(post.getUpdatedAt()).isEqualTo(editedAt);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("Under PRE_MODERATION: effective edit sets pendingCaption, keeps status PUBLISHED, preserves publishedAt, sets reviewRequestedAt, does NOT bump contentVersion")
        void shouldSetPendingCaptionUnderPreModeration() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            boolean changed = post.editCaption(AUTHOR_ID, "Updated caption", editedAt, CommunityPublicationMode.PRE_MODERATION);

            assertThat(changed).isTrue();
            assertThat(post.getCaption()).isEqualTo("Initial caption"); // Original public caption unchanged
            assertThat(post.getPendingCaption()).isEqualTo("Updated caption"); // Candidate caption stored
            assertThat(post.getContentVersion()).isEqualTo(0); // Version not bumped yet
            assertThat(post.getUpdatedAt()).isEqualTo(editedAt);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED); // Status remains PUBLISHED!
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT); // Historical publication timestamp preserved
            assertThat(post.getReviewRequestedAt()).isEqualTo(editedAt);
            assertThat(post.isPendingCaptionEdit()).isTrue();
        }

        @Test
        @DisplayName("Single Candidate Barrier: editing while pendingCaption is present throws CommunityPostPendingEditConflictException")
        void shouldThrowConflictWhenEditingWhilePendingCaptionExists() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt1 = Instant.parse("2026-09-29T10:05:00Z");
            Instant editedAt2 = Instant.parse("2026-09-29T10:10:00Z");

            post.editCaption(AUTHOR_ID, "First candidate", editedAt1, CommunityPublicationMode.PRE_MODERATION);

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "Second candidate", editedAt2, CommunityPublicationMode.PRE_MODERATION))
                    .isInstanceOf(CommunityPostPendingEditConflictException.class)
                    .hasMessageContaining("Bản chỉnh sửa hiện tại đang chờ quản trị viên duyệt.");

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "Second candidate", editedAt2, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostPendingEditConflictException.class)
                    .hasMessageContaining("Bản chỉnh sửa hiện tại đang chờ quản trị viên duyệt.");
        }

        @Test
        @DisplayName("Approve pending caption edit promotes candidate, increments version, clears review queue, preserves publishedAt")
        void shouldApprovePendingCaptionEditOnPublishedPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");
            Instant approvedAt = Instant.parse("2026-09-29T10:30:00Z");

            post.editCaption(AUTHOR_ID, "Approved candidate", editedAt, CommunityPublicationMode.PRE_MODERATION);
            post.approve(approvedAt);

            assertThat(post.getCaption()).isEqualTo("Approved candidate");
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getContentVersion()).isEqualTo(1);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT); // strictly preserved
            assertThat(post.getReviewRequestedAt()).isNull();
            assertThat(post.getUpdatedAt()).isEqualTo(approvedAt);
            assertThat(post.isPendingCaptionEdit()).isFalse();
        }

        @Test
        @DisplayName("Reject pending caption edit discards candidate, keeps original caption, version, and publishedAt")
        void shouldRejectPendingCaptionEditOnPublishedPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");
            Instant rejectedAt = Instant.parse("2026-09-29T10:30:00Z");

            post.editCaption(AUTHOR_ID, "Rejected candidate", editedAt, CommunityPublicationMode.PRE_MODERATION);
            post.reject(rejectedAt);

            assertThat(post.getCaption()).isEqualTo("Initial caption"); // Original caption remains
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
            assertThat(post.getUpdatedAt()).isEqualTo(rejectedAt);
            assertThat(post.isPendingCaptionEdit()).isFalse();
        }

        @Test
        @DisplayName("Hide safety: hiding published post clears pendingCaption so candidate does not survive")
        void shouldClearPendingCaptionWhenHidingPublishedPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");
            Instant hiddenAt = Instant.parse("2026-09-29T10:30:00Z");

            post.editCaption(AUTHOR_ID, "Candidate before hide", editedAt, CommunityPublicationMode.PRE_MODERATION);
            post.hide(hiddenAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            assertThat(post.getCaption()).isEqualTo("Initial caption");
            assertThat(post.getPendingCaption()).isNull();
            assertThat(post.getReviewRequestedAt()).isNull();
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(hiddenAt);
            assertThat(post.isPendingCaptionEdit()).isFalse();
        }

        @Test
        @DisplayName("Should be an idempotent no-op when new caption matches current caption after trimming")
        void shouldBeNoOpWhenCaptionIsIdentical() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Same caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            boolean changed = post.editCaption(AUTHOR_ID, "  Same caption  ", editedAt, CommunityPublicationMode.PRE_MODERATION);

            assertThat(changed).isFalse();
            assertThat(post.getCaption()).isEqualTo("Same caption");
            assertThat(post.getContentVersion()).isEqualTo(0);
            assertThat(post.getUpdatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED); // Not transitioned on no-op
        }

        @Test
        @DisplayName("Should reject editing non-PUBLISHED posts with CommunityPostValidationException")
        void shouldRejectEditingNonPublishedPosts() {
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            CommunityPost pendingPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial", null,
                    CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, CREATED_AT
            );
            assertThatThrownBy(() -> pendingPost.editCaption(AUTHOR_ID, "New caption", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("Only PUBLISHED posts can be edited");

            CommunityPost hiddenPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial", null,
                    CommunityPostStatus.HIDDEN, CREATED_AT, CREATED_AT, null
            );
            assertThatThrownBy(() -> hiddenPost.editCaption(AUTHOR_ID, "New caption", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("Only PUBLISHED posts can be edited");

            CommunityPost rejectedPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial", null,
                    CommunityPostStatus.REJECTED, CREATED_AT, null, null
            );
            assertThatThrownBy(() -> rejectedPost.editCaption(AUTHOR_ID, "New caption", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("Only PUBLISHED posts can be edited");
        }

        @Test
        @DisplayName("Should reject edit by non-author user")
        void shouldRejectEditByNonAuthor() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            assertThatThrownBy(() -> post.editCaption(OTHER_USER_ID, "Hacked caption", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostUnauthorizedException.class)
                    .hasMessageContaining("not authorized");

            assertThatThrownBy(() -> post.editCaption(null, "Hacked caption", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostUnauthorizedException.class);
        }

        @Test
        @DisplayName("Should reject blank new caption or caption exceeding 2000 chars")
        void shouldRejectInvalidNewCaption() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant editedAt = Instant.parse("2026-09-29T10:05:00Z");

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class);

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "   ", editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class);

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "a".repeat(2001), editedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class);
        }

        @Test
        @DisplayName("Should reject edit timestamp before current updatedAt")
        void shouldRejectEditTimestampBeforeUpdatedAt() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Initial caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant beforeCreatedAt = Instant.parse("2026-09-29T09:59:00Z");

            assertThatThrownBy(() -> post.editCaption(AUTHOR_ID, "New caption", beforeCreatedAt, CommunityPublicationMode.AUTO_PUBLISH))
                    .isInstanceOf(CommunityPostValidationException.class)
                    .hasMessageContaining("cannot be before");
        }
    }

    @Nested
    @DisplayName("Lifecycle Transitions")
    class LifecycleTransitionTests {

        @Test
        @DisplayName("approve never-published post: PENDING_REVIEW -> PUBLISHED sets publishedAt to approvedAt")
        void shouldApproveNeverPublishedPendingReviewPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, CREATED_AT
            );
            Instant approvedAt = CREATED_AT.plusSeconds(300);

            post.approve(approvedAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getUpdatedAt()).isEqualTo(approvedAt);
            assertThat(post.getPublishedAt()).isEqualTo(approvedAt);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("approve re-review post: preserves historical publishedAt, nulls reviewRequestedAt")
        void shouldApproveReReviewPostPreservingPublishedAt() {
            Instant initialPublishedAt = CREATED_AT.minusSeconds(1000);
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, CREATED_AT, initialPublishedAt, CREATED_AT
            );
            Instant approvedAt = CREATED_AT.plusSeconds(300);

            post.approve(approvedAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getUpdatedAt()).isEqualTo(approvedAt);
            assertThat(post.getPublishedAt()).isEqualTo(initialPublishedAt); // Preserved!
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("reject: PENDING_REVIEW -> REJECTED succeeds, clears reviewRequestedAt")
        void shouldRejectPendingReviewPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, CREATED_AT
            );
            Instant rejectedAt = CREATED_AT.plusSeconds(300);

            post.reject(rejectedAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.REJECTED);
            assertThat(post.getUpdatedAt()).isEqualTo(rejectedAt);
            assertThat(post.getPublishedAt()).isNull();
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("reject re-review post: previously-published post edited under PRE_MODERATION preserves publishedAt, clears reviewRequestedAt")
        void shouldRejectReReviewPostPreservingPublishedAt() {
            Instant initialPublishedAt = CREATED_AT.minusSeconds(1000);
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, CREATED_AT, initialPublishedAt, CREATED_AT
            );
            Instant rejectedAt = CREATED_AT.plusSeconds(300);

            post.reject(rejectedAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.REJECTED);
            assertThat(post.getUpdatedAt()).isEqualTo(rejectedAt);
            assertThat(post.getPublishedAt()).isEqualTo(initialPublishedAt);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("hide: PUBLISHED -> HIDDEN succeeds, preserves publishedAt, clears reviewRequestedAt")
        void shouldHidePublishedPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            Instant hiddenAt = CREATED_AT.plusSeconds(300);

            post.hide(hiddenAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.HIDDEN);
            assertThat(post.getUpdatedAt()).isEqualTo(hiddenAt);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("restore: HIDDEN -> PUBLISHED succeeds, preserves publishedAt, clears reviewRequestedAt")
        void shouldRestoreHiddenPost() {
            CommunityPost post = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.HIDDEN, CREATED_AT, CREATED_AT, null
            );
            Instant restoredAt = CREATED_AT.plusSeconds(300);

            post.restore(restoredAt);

            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getUpdatedAt()).isEqualTo(restoredAt);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("Invalid transitions throw IllegalStateException")
        void shouldThrowOnInvalidTransitions() {
            Instant now = CREATED_AT.plusSeconds(300);

            CommunityPost publishedPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null
            );
            assertThatThrownBy(() -> publishedPost.approve(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> publishedPost.reject(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> publishedPost.restore(now)).isInstanceOf(IllegalStateException.class);

            CommunityPost hiddenPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.HIDDEN, CREATED_AT, CREATED_AT, null
            );
            assertThatThrownBy(() -> hiddenPost.approve(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> hiddenPost.reject(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> hiddenPost.hide(now)).isInstanceOf(IllegalStateException.class);

            CommunityPost rejectedPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.REJECTED, CREATED_AT, null, null
            );
            assertThatThrownBy(() -> rejectedPost.approve(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> rejectedPost.reject(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> rejectedPost.hide(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> rejectedPost.restore(now)).isInstanceOf(IllegalStateException.class);

            CommunityPost pendingPost = CommunityPost.create(
                    POST_ID, AUTHOR_ID, "Caption", null,
                    CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, CREATED_AT
            );
            assertThatThrownBy(() -> pendingPost.hide(now)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pendingPost.restore(now)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Rehydration Invariants")
    class RehydrationTests {

        @Test
        @DisplayName("Should rehydrate valid persisted post with status and publication timestamps")
        void shouldRehydrateSuccessfully() {
            Instant updatedAt = Instant.parse("2026-09-29T10:15:00Z");
            CommunityPost post = CommunityPost.rehydrate(
                    POST_ID,
                    AUTHOR_ID,
                    "Rehydrated caption",
                    MEDIA_ID,
                    CommunityPostStatus.PUBLISHED,
                    3,
                    CREATED_AT,
                    updatedAt,
                    CREATED_AT,
                    null
            );

            assertThat(post.getId()).isEqualTo(POST_ID);
            assertThat(post.getAuthorUserId()).isEqualTo(AUTHOR_ID);
            assertThat(post.getCaption()).isEqualTo("Rehydrated caption");
            assertThat(post.getImageMediaAssetId()).isEqualTo(MEDIA_ID);
            assertThat(post.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
            assertThat(post.getContentVersion()).isEqualTo(3);
            assertThat(post.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getUpdatedAt()).isEqualTo(updatedAt);
            assertThat(post.getPublishedAt()).isEqualTo(CREATED_AT);
            assertThat(post.getReviewRequestedAt()).isNull();
        }

        @Test
        @DisplayName("Should reject negative contentVersion or updatedAt before createdAt or null status")
        void shouldRejectInvalidRehydrationState() {
            assertThatThrownBy(() -> CommunityPost.rehydrate(
                    POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, -1, CREATED_AT, CREATED_AT, CREATED_AT, null
            )).isInstanceOf(CommunityPostValidationException.class);

            Instant before = Instant.parse("2026-09-29T09:00:00Z");
            assertThatThrownBy(() -> CommunityPost.rehydrate(
                    POST_ID, AUTHOR_ID, "caption", null, CommunityPostStatus.PUBLISHED, 0, CREATED_AT, before, CREATED_AT, null
            )).isInstanceOf(CommunityPostValidationException.class);

            assertThatThrownBy(() -> CommunityPost.rehydrate(
                    POST_ID, AUTHOR_ID, "caption", null, null, 0, CREATED_AT, CREATED_AT, CREATED_AT, null
            )).isInstanceOf(NullPointerException.class);
        }
    }
}
