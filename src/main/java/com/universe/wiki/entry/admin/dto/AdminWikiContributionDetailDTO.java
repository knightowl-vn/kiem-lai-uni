package com.universe.wiki.entry.admin.dto;

import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;

import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Presentation DTO trọn vẹn cho màn hình chi tiết đóng góp Wiki của quản trị viên.
 */
public record AdminWikiContributionDetailDTO(
        UUID contributionId,
        UUID articleId,
        String articleTypeSnapshot,
        String articleTitleSnapshot,
        String articleSlugSnapshot,
        long articleContentVersion,
        AdminWikiContributionContributorDTO contributor,
        WikiContributionContextType contextType,
        WikiContributionType contributionType,
        String message,
        String selectedText,
        String selectedPrefix,
        String selectedSuffix,
        String selectedHeadingAnchor,
        WikiContributionStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<AdminWikiContributionSourceDTO> sources,
        String resolutionNote,
        AdminWikiContributionContributorDTO resolver,
        Instant resolvedAt,
        Long resolvedArticleContentVersion,
        AdminWikiContributionContributorDTO assignee,
        Instant assignedAt,
        AdminWikiContributionContributorDTO reviewStartedBy,
        Instant reviewStartedAt,
        Long reviewStartedArticleContentVersion,
        WikiContributionResolutionOutcome resolutionOutcome,
        List<AdminWikiContributionWorkflowEventDTO> events
) {
    public AdminWikiContributionDetailDTO {
        Objects.requireNonNull(contributionId, "contributionId cannot be null");
        Objects.requireNonNull(articleId, "articleId cannot be null");
        Objects.requireNonNull(contributor, "contributor cannot be null");
        Objects.requireNonNull(contextType, "contextType cannot be null");
        Objects.requireNonNull(contributionType, "contributionType cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        sources = sources != null ? Collections.unmodifiableList(sources) : List.of();
        events = events != null ? Collections.unmodifiableList(events) : List.of();
    }

    public AdminWikiContributionDetailDTO(
            UUID contributionId,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            AdminWikiContributionContributorDTO contributor,
            WikiContributionContextType contextType,
            WikiContributionType contributionType,
            String message,
            String selectedText,
            String selectedPrefix,
            String selectedSuffix,
            String selectedHeadingAnchor,
            WikiContributionStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<AdminWikiContributionSourceDTO> sources,
            String resolutionNote,
            AdminWikiContributionContributorDTO resolver,
            Instant resolvedAt,
            Long resolvedArticleContentVersion
    ) {
        this(contributionId, articleId, articleTypeSnapshot, articleTitleSnapshot, articleSlugSnapshot,
                articleContentVersion, contributor, contextType, contributionType, message,
                selectedText, selectedPrefix, selectedSuffix, selectedHeadingAnchor, status,
                version, createdAt, updatedAt, sources, resolutionNote, resolver, resolvedAt,
                resolvedArticleContentVersion, null, null, null, null, null, null, List.of());
    }

    public boolean hasSelectionEvidence() {
        return contextType == WikiContributionContextType.TEXT_SELECTION
                && selectedText != null && !selectedText.isBlank();
    }

    public boolean hasSources() {
        return !sources.isEmpty();
    }

    public boolean isTerminal() {
        return status == WikiContributionStatus.RESOLVED || status == WikiContributionStatus.REJECTED;
    }
}
