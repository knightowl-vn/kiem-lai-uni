package com.universe.community.application.service;

import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityAuthorProfileSummary;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityPostFeedAuthorEnricher Unit Tests")
class CommunityPostFeedAuthorEnricherTest {

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    @Captor
    private ArgumentCaptor<Set<UUID>> authorIdsCaptor;

    private CommunityPostFeedAuthorEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new CommunityPostFeedAuthorEnricher(authorProfilePort);
    }

    @Test
    @DisplayName("Constructor should throw NullPointerException when authorProfilePort is null")
    void shouldThrowWhenConstructorArgIsNull() {
        assertThatThrownBy(() -> new CommunityPostFeedAuthorEnricher(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("CommunityAuthorProfilePort cannot be null.");
    }

    @Test
    @DisplayName("Empty or null post list should return empty list and make 0 outbound calls")
    void shouldReturnEmptyListForNullOrEmptyInputWithoutOutboundCalls() {
        assertThat(enricher.enrich(null)).isEmpty();
        assertThat(enricher.enrich(List.of())).isEmpty();

        verify(authorProfilePort, never()).findAuthorProfilesByIds(any());
    }

    @Test
    @DisplayName("Multiple posts from same author should trigger exactly 1 bulk call with single distinct UUID")
    void shouldDeduplicateAuthorIdsAndTriggerSingleBulkCall() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item1 = new CommunityPostFeedItemDTO(
                p1Id, authorId, null, null, null, "Post 1",
                null, null, 0,
                5L, 2L, 7L, now, now
        );
        CommunityPostFeedItemDTO item2 = new CommunityPostFeedItemDTO(
                p2Id, authorId, null, null, null, "Post 2",
                null, null, 0,
                10L, 0L, 10L, now, now
        );

        when(authorProfilePort.findAuthorProfilesByIds(Set.of(authorId))).thenReturn(Map.of(
                authorId, new CommunityAuthorProfileSummary(authorId, "quyet_de", "Quyết Đế", "https://cdn.example.com/avatar.png")
        ));

        List<CommunityPostFeedItemDTO> enriched = enricher.enrich(List.of(item1, item2));

        assertThat(enriched).hasSize(2);
        verify(authorProfilePort, times(1)).findAuthorProfilesByIds(authorIdsCaptor.capture());
        assertThat(authorIdsCaptor.getValue()).containsExactly(authorId);

        assertThat(enriched.get(0).authorDisplayName()).isEqualTo("Quyết Đế");
        assertThat(enriched.get(0).authorPublicHandle()).isEqualTo("quyet_de");
        assertThat(enriched.get(0).authorAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");

        assertThat(enriched.get(1).authorDisplayName()).isEqualTo("Quyết Đế");
        assertThat(enriched.get(1).authorPublicHandle()).isEqualTo("quyet_de");
        assertThat(enriched.get(1).authorAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
    }

    @Test
    @DisplayName("Multiple posts from distinct authors should trigger exactly 1 bulk call and enrich corresponding metadata")
    void shouldEnrichDistinctAuthorsInSingleBulkCall() {
        UUID author1 = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item1 = new CommunityPostFeedItemDTO(
                p1Id, author1, null, null, null, "Post 1",
                null, null, 0,
                0L, 0L, 0L, now, now
        );
        CommunityPostFeedItemDTO item2 = new CommunityPostFeedItemDTO(
                p2Id, author2, null, null, null, "Post 2",
                null, null, 0,
                0L, 0L, 0L, now, now
        );

        when(authorProfilePort.findAuthorProfilesByIds(Set.of(author1, author2))).thenReturn(Map.of(
                author1, new CommunityAuthorProfileSummary(author1, "author_1", "Author One", "https://cdn.example.com/1.png"),
                author2, new CommunityAuthorProfileSummary(author2, "author_2", "Author Two", null)
        ));

        List<CommunityPostFeedItemDTO> enriched = enricher.enrich(List.of(item1, item2));

        assertThat(enriched).hasSize(2);
        verify(authorProfilePort, times(1)).findAuthorProfilesByIds(Set.of(author1, author2));

        assertThat(enriched.get(0).authorDisplayName()).isEqualTo("Author One");
        assertThat(enriched.get(0).authorPublicHandle()).isEqualTo("author_1");
        assertThat(enriched.get(0).authorAvatarUrl()).isEqualTo("https://cdn.example.com/1.png");

        assertThat(enriched.get(1).authorDisplayName()).isEqualTo("Author Two");
        assertThat(enriched.get(1).authorPublicHandle()).isEqualTo("author_2");
        assertThat(enriched.get(1).authorAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("Missing author in Identity response should preserve post with null author metadata fields")
    void shouldPreservePostWhenAuthorIsMissingInIdentityResponse() {
        UUID missingAuthorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        CommunityPostFeedItemDTO item1 = new CommunityPostFeedItemDTO(
                p1Id, missingAuthorId, null, null, null, "Post from missing author",
                null, null, 0,
                3L, 1L, 4L, now, now
        );

        when(authorProfilePort.findAuthorProfilesByIds(Set.of(missingAuthorId))).thenReturn(Map.of());

        List<CommunityPostFeedItemDTO> enriched = enricher.enrich(List.of(item1));

        assertThat(enriched).hasSize(1);
        CommunityPostFeedItemDTO result = enriched.get(0);
        assertThat(result.id()).isEqualTo(p1Id);
        assertThat(result.authorUserId()).isEqualTo(missingAuthorId);
        assertThat(result.caption()).isEqualTo("Post from missing author");
        assertThat(result.authorDisplayName()).isNull();
        assertThat(result.authorPublicHandle()).isNull();
        assertThat(result.authorAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("Enrichment must preserve all original post fields, engagement metrics, timestamps, and input order")
    void shouldPreserveAllOriginalFieldsAndOrder() {
        UUID authorId = UUID.randomUUID();
        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-30T11:00:00Z");

        CommunityPostFeedItemDTO item1 = new CommunityPostFeedItemDTO(
                p1Id, authorId, null, null, null, "Caption 1",
                imageId, "/media/assets/" + imageId + "/content", 1,
                15L, 5L, 20L, t1, t1
        );
        CommunityPostFeedItemDTO item2 = new CommunityPostFeedItemDTO(
                p2Id, authorId, null, null, null, "Caption 2",
                null, null, 0,
                2L, 1L, 3L, t2, t2
        );

        when(authorProfilePort.findAuthorProfilesByIds(Set.of(authorId))).thenReturn(Map.of(
                authorId, new CommunityAuthorProfileSummary(authorId, "author_x", "Author X", "https://cdn.example.com/x.jpg")
        ));

        List<CommunityPostFeedItemDTO> enriched = enricher.enrich(List.of(item1, item2));

        assertThat(enriched).hasSize(2);
        // Order must be item1 then item2
        assertThat(enriched.get(0).id()).isEqualTo(p1Id);
        assertThat(enriched.get(0).caption()).isEqualTo("Caption 1");
        assertThat(enriched.get(0).imageMediaAssetId()).isEqualTo(imageId);
        assertThat(enriched.get(0).imageUrl()).isEqualTo("/media/assets/" + imageId + "/content");
        assertThat(enriched.get(0).contentVersion()).isEqualTo(1);
        assertThat(enriched.get(0).reactionCount()).isEqualTo(15L);
        assertThat(enriched.get(0).commentCount()).isEqualTo(5L);
        assertThat(enriched.get(0).engagementScore()).isEqualTo(20L);
        assertThat(enriched.get(0).createdAt()).isEqualTo(t1);

        assertThat(enriched.get(1).id()).isEqualTo(p2Id);
        assertThat(enriched.get(1).caption()).isEqualTo("Caption 2");
        assertThat(enriched.get(1).imageMediaAssetId()).isNull();
        assertThat(enriched.get(1).imageUrl()).isNull();
        assertThat(enriched.get(1).contentVersion()).isEqualTo(0);
        assertThat(enriched.get(1).reactionCount()).isEqualTo(2L);
        assertThat(enriched.get(1).commentCount()).isEqualTo(1L);
        assertThat(enriched.get(1).engagementScore()).isEqualTo(3L);
        assertThat(enriched.get(1).createdAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Enricher strictly preserves publishedAt timestamp when differing from createdAt")
    void shouldPreserveCanonicalPublishedAtWhenDifferentFromCreatedAt() {
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-04T15:00:00Z");
        Instant publishedAt = Instant.parse("2026-10-04T16:20:00Z");

        CommunityPostFeedItemDTO item = new CommunityPostFeedItemDTO(
                postId, authorId, null, null, null, "Approved post",
                null, null, 0,
                0L, 0L, 0L, createdAt, publishedAt, null, publishedAt
        );

        when(authorProfilePort.findAuthorProfilesByIds(Set.of(authorId))).thenReturn(Map.of(
                authorId, new CommunityAuthorProfileSummary(authorId, "author_test", "Author Test", "https://cdn.example.com/a.jpg")
        ));

        List<CommunityPostFeedItemDTO> enriched = enricher.enrich(List.of(item));

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).createdAt()).isEqualTo(createdAt);
        assertThat(enriched.get(0).publishedAt()).isEqualTo(publishedAt);
    }
}
