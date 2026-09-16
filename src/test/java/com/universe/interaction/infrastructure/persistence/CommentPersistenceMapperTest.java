package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.CommentTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentPersistenceMapperTest {

    private static final UUID ROOT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID REPLY_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID CHAPTER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID ARTICLE_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID AUTHOR_USER_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final UUID REPLY_AUTHOR_USER_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final Instant T1 =
            Instant.parse("2026-09-16T10:00:00.123456Z");

    private static final Instant T2 =
            Instant.parse("2026-09-16T11:00:00.654321Z");

    private static final Instant T3 =
            Instant.parse("2026-09-16T12:00:00.999999Z");

    private static final CommentTarget NOVEL_CHAPTER_TARGET =
            CommentTarget.novelChapter(CHAPTER_ID);

    private static final CommentTarget WIKI_ARTICLE_TARGET =
            CommentTarget.wikiArticle(ARTICLE_ID);

    private CommentPersistenceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CommentPersistenceMapper();
    }

    @Nested
    @DisplayName("1. ACTIVE Root Comment Round Trip")
    class ActiveRootCommentRoundTripTests {

        @Test
        @DisplayName("maps ACTIVE root comment to JPA entity and back to domain preserving all fields")
        void shouldMapActiveRootCommentRoundTrip() {
            Comment domain = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Line 1\nLine 2 with   spaces",
                    T1
            );

            CommentJpaEntity jpaEntity = mapper.toJpaEntity(domain);

            assertThat(jpaEntity.getId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getTargetType()).isEqualTo("NOVEL_CHAPTER");
            assertThat(jpaEntity.getTargetId()).isEqualTo(CHAPTER_ID.toString());
            assertThat(jpaEntity.getAuthorUserId()).isEqualTo(AUTHOR_USER_ID.toString());
            assertThat(jpaEntity.getParentCommentId()).isNull();
            assertThat(jpaEntity.getThreadRootCommentId()).isNull();
            assertThat(jpaEntity.getBody()).isEqualTo("Line 1\nLine 2 with   spaces");
            assertThat(jpaEntity.getStatus()).isEqualTo("ACTIVE");
            assertThat(jpaEntity.getCreatedAt()).isEqualTo(T1);
            assertThat(jpaEntity.getUpdatedAt()).isEqualTo(T1);
            assertThat(jpaEntity.getDeletedAt()).isNull();

            Comment reconstituted = mapper.toDomain(jpaEntity);

            assertThat(reconstituted.getId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.getTarget()).isEqualTo(NOVEL_CHAPTER_TARGET);
            assertThat(reconstituted.getTargetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
            assertThat(reconstituted.getTargetId()).isEqualTo(CHAPTER_ID);
            assertThat(reconstituted.getAuthorUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(reconstituted.getParentCommentId()).isNull();
            assertThat(reconstituted.getThreadRootCommentId()).isNull();
            assertThat(reconstituted.isRoot()).isTrue();
            assertThat(reconstituted.isReply()).isFalse();
            assertThat(reconstituted.getBody()).isEqualTo("Line 1\nLine 2 with   spaces");
            assertThat(reconstituted.getStatus()).isEqualTo(CommentStatus.ACTIVE);
            assertThat(reconstituted.isActive()).isTrue();
            assertThat(reconstituted.isDeleted()).isFalse();
            assertThat(reconstituted.getCreatedAt()).isEqualTo(T1);
            assertThat(reconstituted.getUpdatedAt()).isEqualTo(T1);
            assertThat(reconstituted.getDeletedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("2. ACTIVE Reply Round Trip")
    class ActiveReplyRoundTripTests {

        @Test
        @DisplayName("maps ACTIVE reply to JPA entity and back to domain preserving parentCommentId and threadRootCommentId")
        void shouldMapActiveReplyRoundTrip() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    WIKI_ARTICLE_TARGET,
                    AUTHOR_USER_ID,
                    "Root wiki comment",
                    T1
            );

            Comment reply = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply to wiki comment",
                    T2
            );

            CommentJpaEntity jpaEntity = mapper.toJpaEntity(reply);

            assertThat(jpaEntity.getId()).isEqualTo(REPLY_ID.toString());
            assertThat(jpaEntity.getTargetType()).isEqualTo("WIKI_ARTICLE");
            assertThat(jpaEntity.getTargetId()).isEqualTo(ARTICLE_ID.toString());
            assertThat(jpaEntity.getAuthorUserId()).isEqualTo(REPLY_AUTHOR_USER_ID.toString());
            assertThat(jpaEntity.getParentCommentId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getThreadRootCommentId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getBody()).isEqualTo("Reply to wiki comment");
            assertThat(jpaEntity.getStatus()).isEqualTo("ACTIVE");
            assertThat(jpaEntity.getCreatedAt()).isEqualTo(T2);
            assertThat(jpaEntity.getUpdatedAt()).isEqualTo(T2);
            assertThat(jpaEntity.getDeletedAt()).isNull();

            Comment reconstituted = mapper.toDomain(jpaEntity);

            assertThat(reconstituted.getId()).isEqualTo(REPLY_ID);
            assertThat(reconstituted.getTarget()).isEqualTo(WIKI_ARTICLE_TARGET);
            assertThat(reconstituted.getTargetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
            assertThat(reconstituted.getTargetId()).isEqualTo(ARTICLE_ID);
            assertThat(reconstituted.getAuthorUserId()).isEqualTo(REPLY_AUTHOR_USER_ID);
            assertThat(reconstituted.getParentCommentId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.getThreadRootCommentId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.isRoot()).isFalse();
            assertThat(reconstituted.isReply()).isTrue();
            assertThat(reconstituted.getBody()).isEqualTo("Reply to wiki comment");
            assertThat(reconstituted.getStatus()).isEqualTo(CommentStatus.ACTIVE);
            assertThat(reconstituted.getCreatedAt()).isEqualTo(T2);
            assertThat(reconstituted.getUpdatedAt()).isEqualTo(T2);
            assertThat(reconstituted.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("maps ACTIVE nested reply preserving parentCommentId and threadRootCommentId")
        void shouldMapActiveNestedReplyRoundTrip() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root novel comment",
                    T1
            );

            Comment replyB = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply B",
                    T2
            );

            UUID replyCId = UUID.fromString("77777777-7777-7777-7777-777777777777");
            UUID authorC = UUID.fromString("88888888-8888-8888-8888-888888888888");
            Comment replyC = Comment.createReply(
                    replyCId,
                    replyB,
                    authorC,
                    "Nested reply C to reply B",
                    T3
            );

            CommentJpaEntity jpaEntity = mapper.toJpaEntity(replyC);

            assertThat(jpaEntity.getId()).isEqualTo(replyCId.toString());
            assertThat(jpaEntity.getTargetType()).isEqualTo("NOVEL_CHAPTER");
            assertThat(jpaEntity.getTargetId()).isEqualTo(CHAPTER_ID.toString());
            assertThat(jpaEntity.getAuthorUserId()).isEqualTo(authorC.toString());
            assertThat(jpaEntity.getParentCommentId()).isEqualTo(REPLY_ID.toString());
            assertThat(jpaEntity.getThreadRootCommentId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getBody()).isEqualTo("Nested reply C to reply B");
            assertThat(jpaEntity.getStatus()).isEqualTo("ACTIVE");

            Comment reconstituted = mapper.toDomain(jpaEntity);

            assertThat(reconstituted.getId()).isEqualTo(replyCId);
            assertThat(reconstituted.getParentCommentId()).isEqualTo(REPLY_ID);
            assertThat(reconstituted.getThreadRootCommentId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.isRoot()).isFalse();
            assertThat(reconstituted.isReply()).isTrue();
            assertThat(reconstituted.getBody()).isEqualTo("Nested reply C to reply B");
        }
    }

    @Nested
    @DisplayName("3. DELETED Root Tombstone Round Trip")
    class DeletedRootTombstoneRoundTripTests {

        @Test
        @DisplayName("maps DELETED root tombstone to JPA entity and back ensuring body is null")
        void shouldMapDeletedRootTombstoneRoundTrip() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Body before delete",
                    T1
            );
            root.delete(T2);

            CommentJpaEntity jpaEntity = mapper.toJpaEntity(root);

            assertThat(jpaEntity.getId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getParentCommentId()).isNull();
            assertThat(jpaEntity.getThreadRootCommentId()).isNull();
            assertThat(jpaEntity.getBody()).isNull();
            assertThat(jpaEntity.getStatus()).isEqualTo("DELETED");
            assertThat(jpaEntity.getCreatedAt()).isEqualTo(T1);
            assertThat(jpaEntity.getUpdatedAt()).isEqualTo(T2);
            assertThat(jpaEntity.getDeletedAt()).isEqualTo(T2);

            Comment reconstituted = mapper.toDomain(jpaEntity);

            assertThat(reconstituted.getId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.getTarget()).isEqualTo(NOVEL_CHAPTER_TARGET);
            assertThat(reconstituted.getAuthorUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(reconstituted.getParentCommentId()).isNull();
            assertThat(reconstituted.getThreadRootCommentId()).isNull();
            assertThat(reconstituted.getBody()).isNull();
            assertThat(reconstituted.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(reconstituted.isDeleted()).isTrue();
            assertThat(reconstituted.isActive()).isFalse();
            assertThat(reconstituted.getCreatedAt()).isEqualTo(T1);
            assertThat(reconstituted.getUpdatedAt()).isEqualTo(T2);
            assertThat(reconstituted.getDeletedAt()).isEqualTo(T2);
        }
    }

    @Nested
    @DisplayName("4. DELETED Reply Tombstone Round Trip")
    class DeletedReplyTombstoneRoundTripTests {

        @Test
        @DisplayName("maps DELETED reply tombstone preserving parentCommentId, threadRootCommentId, and author while body is null")
        void shouldMapDeletedReplyTombstoneRoundTrip() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root body",
                    T1
            );
            Comment reply = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply body",
                    T2
            );
            reply.delete(T3);

            CommentJpaEntity jpaEntity = mapper.toJpaEntity(reply);

            assertThat(jpaEntity.getId()).isEqualTo(REPLY_ID.toString());
            assertThat(jpaEntity.getParentCommentId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getThreadRootCommentId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getAuthorUserId()).isEqualTo(REPLY_AUTHOR_USER_ID.toString());
            assertThat(jpaEntity.getBody()).isNull();
            assertThat(jpaEntity.getStatus()).isEqualTo("DELETED");
            assertThat(jpaEntity.getCreatedAt()).isEqualTo(T2);
            assertThat(jpaEntity.getUpdatedAt()).isEqualTo(T3);
            assertThat(jpaEntity.getDeletedAt()).isEqualTo(T3);

            Comment reconstituted = mapper.toDomain(jpaEntity);

            assertThat(reconstituted.getId()).isEqualTo(REPLY_ID);
            assertThat(reconstituted.getParentCommentId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.getThreadRootCommentId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.getAuthorUserId()).isEqualTo(REPLY_AUTHOR_USER_ID);
            assertThat(reconstituted.getBody()).isNull();
            assertThat(reconstituted.getStatus()).isEqualTo(CommentStatus.DELETED);
            assertThat(reconstituted.isDeleted()).isTrue();
            assertThat(reconstituted.isReply()).isTrue();
            assertThat(reconstituted.getCreatedAt()).isEqualTo(T2);
            assertThat(reconstituted.getUpdatedAt()).isEqualTo(T3);
            assertThat(reconstituted.getDeletedAt()).isEqualTo(T3);
        }

        @Test
        @DisplayName("maps DELETED nested reply preserving parentCommentId and threadRootCommentId while body is null")
        void shouldMapDeletedNestedReplyTombstoneRoundTrip() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Root body",
                    T1
            );
            Comment replyB = Comment.createReply(
                    REPLY_ID,
                    root,
                    REPLY_AUTHOR_USER_ID,
                    "Reply B body",
                    T2
            );
            UUID replyCId = UUID.fromString("77777777-7777-7777-7777-777777777777");
            UUID authorC = UUID.fromString("88888888-8888-8888-8888-888888888888");
            Comment replyC = Comment.createReply(
                    replyCId,
                    replyB,
                    authorC,
                    "Reply C body",
                    T3
            );
            replyC.delete(Instant.parse("2026-09-16T13:00:00Z"));

            CommentJpaEntity jpaEntity = mapper.toJpaEntity(replyC);

            assertThat(jpaEntity.getId()).isEqualTo(replyCId.toString());
            assertThat(jpaEntity.getParentCommentId()).isEqualTo(REPLY_ID.toString());
            assertThat(jpaEntity.getThreadRootCommentId()).isEqualTo(ROOT_ID.toString());
            assertThat(jpaEntity.getBody()).isNull();
            assertThat(jpaEntity.getStatus()).isEqualTo("DELETED");

            Comment reconstituted = mapper.toDomain(jpaEntity);

            assertThat(reconstituted.getId()).isEqualTo(replyCId);
            assertThat(reconstituted.getParentCommentId()).isEqualTo(REPLY_ID);
            assertThat(reconstituted.getThreadRootCommentId()).isEqualTo(ROOT_ID);
            assertThat(reconstituted.getBody()).isNull();
            assertThat(reconstituted.isDeleted()).isTrue();
            assertThat(reconstituted.isReply()).isTrue();
        }
    }

    @Nested
    @DisplayName("5. UUID / String Exact Conversion")
    class UuidConversionTests {

        @Test
        @DisplayName("rejects invalid UUID strings in JPA entity")
        void shouldRejectInvalidUuidStrings() {
            CommentJpaEntity invalidId = new CommentJpaEntity(
                    "not-a-uuid",
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    null,
                    null,
                    "Valid body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(invalidId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Comment ID in database has invalid UUID format");

            CommentJpaEntity invalidTargetId = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    "not-a-uuid",
                    AUTHOR_USER_ID.toString(),
                    null,
                    null,
                    "Valid body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(invalidTargetId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Target ID in database has invalid UUID format");

            CommentJpaEntity invalidAuthorId = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    "not-a-uuid",
                    null,
                    null,
                    "Valid body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(invalidAuthorId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Author user ID in database has invalid UUID format");

            CommentJpaEntity invalidParentId = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    "not-a-uuid",
                    ROOT_ID.toString(),
                    "Valid body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(invalidParentId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Parent comment ID in database has invalid UUID format");

            CommentJpaEntity invalidThreadRootId = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    ROOT_ID.toString(),
                    "not-a-uuid",
                    "Valid body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(invalidThreadRootId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Thread root comment ID in database has invalid UUID format");
        }
    }

    @Nested
    @DisplayName("6. Target Type Contract")
    class TargetTypeTests {

        @Test
        @DisplayName("rejects invalid or unsupported target types in JPA entity")
        void shouldRejectInvalidTargetType() {
            CommentJpaEntity entity = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "DONGHUA_EPISODE",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    null,
                    null,
                    "Valid body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(entity))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid target type in database");
        }
    }

    @Nested
    @DisplayName("7. Status Contract")
    class StatusContractTests {

        @Test
        @DisplayName("rejects invalid status strings in JPA entity")
        void shouldRejectInvalidStatus() {
            CommentJpaEntity entity = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    null,
                    null,
                    "Valid body",
                    "MODERATED",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(entity))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid comment status in database");
        }
    }

    @Nested
    @DisplayName("8. Null Input Validation")
    class NullInputValidationTests {

        @Test
        @DisplayName("toJpaEntity rejects null domain comment")
        void shouldRejectNullDomainComment() {
            assertThatThrownBy(() -> mapper.toJpaEntity(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Domain comment cannot be null");
        }

        @Test
        @DisplayName("toDomain rejects null JPA entity")
        void shouldRejectNullJpaEntity() {
            assertThatThrownBy(() -> mapper.toDomain(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CommentJpaEntity cannot be null");
        }
    }

    @Nested
    @DisplayName("9. Precision and Invariant Enforcement via Rehydration")
    class PrecisionAndInvariantTests {

        @Test
        @DisplayName("preserves microsecond precision on timestamps")
        void shouldPreserveTimestampPrecision() {
            Comment root = Comment.createRoot(
                    ROOT_ID,
                    NOVEL_CHAPTER_TARGET,
                    AUTHOR_USER_ID,
                    "Timestamp precision body",
                    T1
            );

            CommentJpaEntity jpa = mapper.toJpaEntity(root);
            Comment reconstituted = mapper.toDomain(jpa);

            assertThat(reconstituted.getCreatedAt()).isEqualTo(T1);
            assertThat(reconstituted.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        @DisplayName("rehydration via toDomain enforces DELETED body must be null")
        void shouldEnforceDeletedBodyMustBeNullViaToDomain() {
            CommentJpaEntity invalidDeletedEntity = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    null,
                    null,
                    "Non-null body on deleted",
                    "DELETED",
                    T1,
                    T2,
                    T2
            );

            assertThatThrownBy(() -> mapper.toDomain(invalidDeletedEntity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Body must be null for a DELETED comment");
        }

        @Test
        @DisplayName("rehydration via toDomain enforces DELETED updatedAt must equal deletedAt")
        void shouldEnforceDeletedUpdatedAtEqualsDeletedAtViaToDomain() {
            CommentJpaEntity mismatchedTimestamps = new CommentJpaEntity(
                    ROOT_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    null,
                    null,
                    null,
                    "DELETED",
                    T1,
                    T3,
                    T2
            );

            assertThatThrownBy(() -> mapper.toDomain(mismatchedTimestamps))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("UpdatedAt timestamp must equal deletedAt timestamp for a DELETED comment");
        }

        @Test
        @DisplayName("rehydration via toDomain enforces thread hierarchy invariants")
        void shouldEnforceThreadHierarchyInvariantsViaToDomain() {
            CommentJpaEntity hierarchyMismatchEntity = new CommentJpaEntity(
                    REPLY_ID.toString(),
                    "NOVEL_CHAPTER",
                    CHAPTER_ID.toString(),
                    AUTHOR_USER_ID.toString(),
                    ROOT_ID.toString(),
                    null, // thread_root_comment_id is null while parent is present
                    "Body",
                    "ACTIVE",
                    T1,
                    T1,
                    null
            );

            assertThatThrownBy(() -> mapper.toDomain(hierarchyMismatchEntity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Comment thread hierarchy mismatch");
        }
    }
}
