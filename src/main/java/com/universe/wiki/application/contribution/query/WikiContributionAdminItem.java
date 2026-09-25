package com.universe.wiki.application.contribution.query;

import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Triage inbox projection item of a Wiki contribution for queue triage.
 *
 * <p>Contains only metadata necessary for queue triage. Detailed evidence
 * (full message, selected text, anchor locators, source URLs) belongs exclusively
 * to the H7 contribution detail view.
 */
public record WikiContributionAdminItem(
        UUID contributionId,
        WikiContributionStatus status,
        WikiContributionType contributionType,
        WikiContributionContextType contextType,
        UUID articleId,
        String articleTypeSnapshot,
        String articleTitleSnapshot,
        String articleSlugSnapshot,
        long articleContentVersion,
        UUID submittedByUserId,
        UUID assignedToUserId,
        String messagePreview,
        boolean hasSelectedText,
        boolean hasSources,
        int sourceCount,
        Instant createdAt
) {
    public WikiContributionAdminItem {
        Objects.requireNonNull(contributionId, "contributionId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(contributionType, "contributionType cannot be null");
        Objects.requireNonNull(contextType, "contextType cannot be null");
        Objects.requireNonNull(articleId, "articleId cannot be null");
        Objects.requireNonNull(submittedByUserId, "submittedByUserId cannot be null");
        Objects.requireNonNull(messagePreview, "messagePreview cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    public WikiContributionAdminItem(
            UUID contributionId,
            WikiContributionStatus status,
            WikiContributionType contributionType,
            WikiContributionContextType contextType,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            UUID submittedByUserId,
            String messagePreview,
            boolean hasSelectedText,
            boolean hasSources,
            int sourceCount,
            Instant createdAt
    ) {
        this(contributionId, status, contributionType, contextType, articleId, articleTypeSnapshot,
                articleTitleSnapshot, articleSlugSnapshot, articleContentVersion, submittedByUserId,
                null, messagePreview, hasSelectedText, hasSources, sourceCount, createdAt);
    }
}
