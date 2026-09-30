package com.universe.community.infrastructure.interaction;

import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.interaction.application.ports.CommunityPostEngagementQueryPort;
import com.universe.interaction.application.query.CommunityPostEngagementCountsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionCommunityPostEngagementAdapter Unit Tests")
class InteractionCommunityPostEngagementAdapterTest {

    @Mock
    private CommunityPostEngagementQueryPort interactionQueryPort;

    private InteractionCommunityPostEngagementAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InteractionCommunityPostEngagementAdapter(interactionQueryPort);
    }

    @Test
    @DisplayName("Should return empty map on null or empty post IDs")
    void shouldReturnEmptyMapOnEmptyInput() {
        assertThat(adapter.getEngagementMetricsForPosts(null)).isEmpty();
        assertThat(adapter.getEngagementMetricsForPosts(List.of())).isEmpty();
        verifyNoInteractions(interactionQueryPort);
    }

    @Test
    @DisplayName("Should delegate to Interaction port and map DTOs to Community engagement metrics")
    void shouldDelegateAndMapMetrics() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        when(interactionQueryPort.getEngagementCountsForPosts(List.of(p1, p2))).thenReturn(Map.of(
                p1, new CommunityPostEngagementCountsDTO(p1, 15L, 4L),
                p2, new CommunityPostEngagementCountsDTO(p2, 0L, 2L)
        ));

        Map<UUID, CommunityPostEngagementMetricsPort.PostEngagementMetrics> metrics =
                adapter.getEngagementMetricsForPosts(List.of(p1, p2));

        assertThat(metrics).hasSize(2);
        assertThat(metrics.get(p1).reactionCount()).isEqualTo(15L);
        assertThat(metrics.get(p1).commentCount()).isEqualTo(4L);

        assertThat(metrics.get(p2).reactionCount()).isEqualTo(0L);
        assertThat(metrics.get(p2).commentCount()).isEqualTo(2L);

        verify(interactionQueryPort).getEngagementCountsForPosts(List.of(p1, p2));
    }
}
