package com.universe.community.infrastructure.interaction;

import com.universe.community.application.port.out.CommunityPostReportQueryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.ReportTargetType;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure adapter implementing {@link CommunityPostReportQueryPort} by delegating
 * to Interaction context's report repository port.
 */
@Component
public class InteractionCommunityPostReportQueryAdapter implements CommunityPostReportQueryPort {

    private final InteractionReportRepositoryPort reportRepositoryPort;

    public InteractionCommunityPostReportQueryAdapter(InteractionReportRepositoryPort reportRepositoryPort) {
        this.reportRepositoryPort = Objects.requireNonNull(
                reportRepositoryPort,
                "InteractionReportRepositoryPort cannot be null."
        );
    }

    @Override
    public boolean hasPendingReports(UUID postId) {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID cannot be null.");
        }
        return reportRepositoryPort.existsPendingByTarget(ReportTargetType.COMMUNITY_POST, postId);
    }
}
