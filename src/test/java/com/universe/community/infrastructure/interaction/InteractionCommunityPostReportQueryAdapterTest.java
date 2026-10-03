package com.universe.community.infrastructure.interaction;

import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionCommunityPostReportQueryAdapter Unit Tests")
class InteractionCommunityPostReportQueryAdapterTest {

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    private InteractionCommunityPostReportQueryAdapter adapter;

    private static final UUID POST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        adapter = new InteractionCommunityPostReportQueryAdapter(reportRepositoryPort);
    }

    @Test
    @DisplayName("Constructor rejects null repository port")
    void constructorRejectsNull() {
        assertThatThrownBy(() -> new InteractionCommunityPostReportQueryAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("InteractionReportRepositoryPort cannot be null.");
    }

    @Test
    @DisplayName("hasPendingReports rejects null post ID")
    void hasPendingReportsRejectsNullPostId() {
        assertThatThrownBy(() -> adapter.hasPendingReports(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Post ID cannot be null.");
    }

    @Test
    @DisplayName("hasPendingReports delegates to reportRepositoryPort with COMMUNITY_POST target type")
    void hasPendingReportsDelegatesCorrectly() {
        when(reportRepositoryPort.existsPendingByTarget(ReportTargetType.COMMUNITY_POST, POST_ID))
                .thenReturn(true);

        boolean result = adapter.hasPendingReports(POST_ID);

        assertThat(result).isTrue();
        verify(reportRepositoryPort).existsPendingByTarget(ReportTargetType.COMMUNITY_POST, POST_ID);
    }

    @Test
    @DisplayName("hasPendingReports returns false when repository returns false")
    void hasPendingReportsReturnsFalse() {
        when(reportRepositoryPort.existsPendingByTarget(ReportTargetType.COMMUNITY_POST, POST_ID))
                .thenReturn(false);

        boolean result = adapter.hasPendingReports(POST_ID);

        assertThat(result).isFalse();
        verify(reportRepositoryPort).existsPendingByTarget(ReportTargetType.COMMUNITY_POST, POST_ID);
    }
}
