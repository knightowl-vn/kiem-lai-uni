package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.CommentRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentRevisionPersistenceMapper Unit Tests")
class CommentRevisionPersistenceMapperTest {

    private static final UUID REVISION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID COMMENT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant CREATED_AT = Instant.parse("2026-09-18T14:30:00.123456Z");

    private CommentRevisionPersistenceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CommentRevisionPersistenceMapper();
    }

    @Test
    @DisplayName("Should map domain to JPA entity with exact scalar values")
    void shouldMapDomainToJpaEntity() {
        CommentRevision domain = new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                1,
                "Original comment body",
                CREATED_AT
        );

        CommentRevisionJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(REVISION_ID.toString());
        assertThat(entity.getCommentId()).isEqualTo(COMMENT_ID.toString());
        assertThat(entity.getRevisionNumber()).isEqualTo(1);
        assertThat(entity.getBody()).isEqualTo("Original comment body");
        assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Should map JPA entity to domain with exact values")
    void shouldMapJpaEntityToDomain() {
        CommentRevisionJpaEntity entity = new CommentRevisionJpaEntity(
                REVISION_ID.toString(),
                COMMENT_ID.toString(),
                2,
                "Second revision body",
                CREATED_AT
        );

        CommentRevision domain = mapper.toDomain(entity);

        assertThat(domain.getId()).isEqualTo(REVISION_ID);
        assertThat(domain.getCommentId()).isEqualTo(COMMENT_ID);
        assertThat(domain.getRevisionNumber()).isEqualTo(2);
        assertThat(domain.getBody()).isEqualTo("Second revision body");
        assertThat(domain.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("Should complete round trip without state loss")
    void shouldCompleteRoundTripWithoutLoss() {
        CommentRevision original = new CommentRevision(
                REVISION_ID,
                COMMENT_ID,
                3,
                "Third revision body",
                CREATED_AT
        );

        CommentRevisionJpaEntity entity = mapper.toJpaEntity(original);
        CommentRevision roundTripped = mapper.toDomain(entity);

        assertThat(roundTripped).isEqualTo(original);
        assertThat(roundTripped.getId()).isEqualTo(original.getId());
        assertThat(roundTripped.getCommentId()).isEqualTo(original.getCommentId());
        assertThat(roundTripped.getRevisionNumber()).isEqualTo(original.getRevisionNumber());
        assertThat(roundTripped.getBody()).isEqualTo(original.getBody());
        assertThat(roundTripped.getCreatedAt()).isEqualTo(original.getCreatedAt());
    }

    @Test
    @DisplayName("Should reject null domain object")
    void shouldRejectNullDomain() {
        assertThatThrownBy(() -> mapper.toJpaEntity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Domain CommentRevision cannot be null.");
    }

    @Test
    @DisplayName("Should reject null JPA entity")
    void shouldRejectNullEntity() {
        assertThatThrownBy(() -> mapper.toDomain(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CommentRevisionJpaEntity cannot be null.");
    }

    @Test
    @DisplayName("Should reject invalid UUID in entity ID")
    void shouldRejectInvalidRevisionIdInEntity() {
        CommentRevisionJpaEntity entity = new CommentRevisionJpaEntity(
                "invalid-uuid",
                COMMENT_ID.toString(),
                1,
                "Body",
                CREATED_AT
        );

        assertThatThrownBy(() -> mapper.toDomain(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Revision ID in database has invalid UUID format: invalid-uuid");
    }

    @Test
    @DisplayName("Should reject invalid UUID in entity commentId")
    void shouldRejectInvalidCommentIdInEntity() {
        CommentRevisionJpaEntity entity = new CommentRevisionJpaEntity(
                REVISION_ID.toString(),
                "invalid-comment-id",
                1,
                "Body",
                CREATED_AT
        );

        assertThatThrownBy(() -> mapper.toDomain(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Comment ID in database has invalid UUID format: invalid-comment-id");
    }
}
