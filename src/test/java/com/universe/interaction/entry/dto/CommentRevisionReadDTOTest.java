package com.universe.interaction.entry.dto;

import com.universe.interaction.domain.CommentRevision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentRevisionReadDTO Unit & Privacy Contract Tests")
class CommentRevisionReadDTOTest {

    @Test
    @DisplayName("Should map strictly public fields from domain CommentRevision")
    void shouldMapStrictlyPublicFields() {
        UUID revisionId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-18T10:00:00Z");

        CommentRevision domain = new CommentRevision(revisionId, commentId, 2, "Public revision body", createdAt);
        CommentRevisionReadDTO dto = CommentRevisionReadDTO.from(domain);

        assertThat(dto.revisionNumber()).isEqualTo(2);
        assertThat(dto.body()).isEqualTo("Public revision body");
        assertThat(dto.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("Should never expose internal identifiers, author information, or moderation metadata")
    void shouldNeverExposeInternalOrAuthorMetadata() {
        Set<String> declaredFieldNames = Arrays.stream(CommentRevisionReadDTO.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        // Must strictly contain only revisionNumber, body, createdAt
        assertThat(declaredFieldNames).containsExactlyInAnyOrder("revisionNumber", "body", "createdAt");

        // Explicitly assert absence of private / internal fields
        assertThat(declaredFieldNames).doesNotContain(
                "id", "revisionId", "commentId", "authorUserId", "author", "authorName",
                "displayName", "avatar", "avatarUrl", "status", "moderation", "moderatedAt"
        );
    }

    @Test
    @DisplayName("Should enforce non-null invariants for body and createdAt")
    void shouldEnforceNonNullInvariants() {
        Instant now = Instant.now();

        assertThatThrownBy(() -> new CommentRevisionReadDTO(1, null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Revision body cannot be null");

        assertThatThrownBy(() -> new CommentRevisionReadDTO(1, "Body", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp cannot be null");
    }

    @Test
    @DisplayName("CommentRevisionSliceResponseDTO should preserve slice pagination semantics")
    void shouldPreserveSliceResponseStructure() {
        CommentRevisionReadDTO item = new CommentRevisionReadDTO(1, "Body", Instant.now());
        CommentRevisionSliceResponseDTO sliceDTO = new CommentRevisionSliceResponseDTO(List.of(item), 0, 20, true);

        assertThat(sliceDTO.items()).hasSize(1);
        assertThat(sliceDTO.page()).isEqualTo(0);
        assertThat(sliceDTO.size()).isEqualTo(20);
        assertThat(sliceDTO.hasNext()).isTrue();
    }
}
