package com.universe.interaction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentRevision Domain Record Invariants")
class CommentRevisionTest {

    private static final UUID REVISION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    @DisplayName("Should instantiate valid comment revision")
    void shouldInstantiateValidRevision() {
        CommentRevision revision = new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                1,
                "Initial comment body",
                CREATED_AT
        );

        assertThat(revision.getId()).isEqualTo(REVISION_ID);
        assertThat(revision.getCommentId()).isEqualTo(COMMENT_ID);
        assertThat(revision.getRevisionNumber()).isEqualTo(1);
        assertThat(revision.getBody()).isEqualTo("Initial comment body");
        assertThat(revision.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Should normalize body by trimming whitespace")
    void shouldNormalizeBodyWhitespace() {
        CommentRevision revision = new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                2,
                "   Whitespace-padded body   \n",
                CREATED_AT
        );

        assertThat(revision.getBody()).isEqualTo("Whitespace-padded body");
    }

    @Test
    @DisplayName("Should reject null revision ID")
    void shouldRejectNullRevisionId() {
        assertThatThrownBy(() -> new CommentRevision(
                null,
                COMMENT_ID,
                1,
                "Body",
                CREATED_AT
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Revision ID cannot be null.");
    }

    @Test
    @DisplayName("Should reject null comment ID")
    void shouldRejectNullCommentId() {
        assertThatThrownBy(() -> new CommentRevision(
                REVISION_ID,
                null,
                1,
                "Body",
                CREATED_AT
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Comment ID cannot be null.");
    }

    @Test
    @DisplayName("Should reject revision number 0")
    void shouldRejectRevisionNumberZero() {
        assertThatThrownBy(() -> new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                0,
                "Body",
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Revision number must be greater than or equal to 1: 0");
    }

    @Test
    @DisplayName("Should reject negative revision number")
    void shouldRejectNegativeRevisionNumber() {
        assertThatThrownBy(() -> new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                -1,
                "Body",
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Revision number must be greater than or equal to 1: -1");
    }

    @Test
    @DisplayName("Should reject null body")
    void shouldRejectNullBody() {
        assertThatThrownBy(() -> new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                1,
                null,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Revision body cannot be null.");
    }

    @Test
    @DisplayName("Should reject blank body")
    void shouldRejectBlankBody() {
        assertThatThrownBy(() -> new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                1,
                "   ",
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Revision body cannot be blank.");
    }

    @Test
    @DisplayName("Should reject null createdAt")
    void shouldRejectNullCreatedAt() {
        assertThatThrownBy(() -> new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                1,
                "Body",
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp cannot be null.");
    }

    @Test
    @DisplayName("Should satisfy value object equality and hashcode")
    void shouldSatisfyEqualityAndHashCode() {
        CommentRevision r1 = new CommentRevision(REVISION_ID, COMMENT_ID, 1, "Body", CREATED_AT);
        CommentRevision r2 = new CommentRevision(REVISION_ID, COMMENT_ID, 1, "Body", CREATED_AT);
        CommentRevision r3 = new CommentRevision(UUID.randomUUID(), COMMENT_ID, 2, "Body 2", CREATED_AT);

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        assertThat(r1).isNotEqualTo(r3);
    }
}
