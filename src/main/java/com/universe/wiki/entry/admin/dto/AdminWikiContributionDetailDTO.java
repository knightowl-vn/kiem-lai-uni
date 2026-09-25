package com.universe.wiki.entry.admin.dto;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;

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
        List<AdminWikiContributionWorkflowEventDTO> events,
        AdminWikiContributionCreditDTO credit
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

    public boolean hasCredit() {
        return credit != null;
    }

    public boolean isCreditableOutcome() {
        return status == WikiContributionStatus.RESOLVED
                && (resolutionOutcome == WikiContributionResolutionOutcome.APPLIED
                || resolutionOutcome == WikiContributionResolutionOutcome.NO_CHANGE_NEEDED);
    }

    public boolean canGrantCredit(AuthenticatedRequestIdentity currentAdmin) {
        if (currentAdmin == null || currentAdmin.status() != UserStatus.ACTIVE) {
            return false;
        }
        if (!isCreditableOutcome()) {
            return false;
        }
        if (hasCredit()) {
            return false;
        }
        boolean isAdmin = currentAdmin.role() == UserRole.ADMIN;
        boolean isSuperAdmin = currentAdmin.role() == UserRole.SUPER_ADMIN;
        if (!isAdmin && !isSuperAdmin) {
            return false;
        }
        boolean isResolver = resolver != null && currentAdmin.userId().equals(resolver.userId());
        return isSuperAdmin || isResolver;
    }

    public boolean canRevokeCredit(AuthenticatedRequestIdentity currentAdmin) {
        if (currentAdmin == null || currentAdmin.status() != UserStatus.ACTIVE) {
            return false;
        }
        if (credit == null || !credit.isActive()) {
            return false;
        }
        return currentAdmin.role() == UserRole.SUPER_ADMIN;
    }
}
