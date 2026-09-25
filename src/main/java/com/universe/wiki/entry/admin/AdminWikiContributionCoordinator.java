package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.contribution.query.GetWikiContributionAdminDetailUseCase;
import com.universe.wiki.application.contribution.query.GetWikiContributionAdminInboxUseCase;
import com.universe.wiki.application.contribution.query.WikiContributionAdminDetail;
import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.application.contribution.query.WikiContributionAdminItem;
import com.universe.wiki.application.contribution.query.WikiContributionAdminPage;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionContributorDTO;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionDetailDTO;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueueItemDTO;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionSourceDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Entry coordinator for the Admin Wiki Contribution Inbox and Detail inspection.
 *
 * <p>Enforces bounded-context isolation and N+1 query prevention by performing at most
 * 1 bulk query to {@link UserIdentityContract} for contributor public profiles per page/view.
 */
@Service
public class AdminWikiContributionCoordinator {

    private final GetWikiContributionAdminInboxUseCase inboxUseCase;
    private final GetWikiContributionAdminDetailUseCase detailUseCase;
    private final UserIdentityContract userIdentityContract;

    public AdminWikiContributionCoordinator(
            GetWikiContributionAdminInboxUseCase inboxUseCase,
            GetWikiContributionAdminDetailUseCase detailUseCase,
            UserIdentityContract userIdentityContract
    ) {
        this.inboxUseCase = Objects.requireNonNull(inboxUseCase, "GetWikiContributionAdminInboxUseCase cannot be null");
        this.detailUseCase = Objects.requireNonNull(detailUseCase, "GetWikiContributionAdminDetailUseCase cannot be null");
        this.userIdentityContract = Objects.requireNonNull(userIdentityContract, "UserIdentityContract cannot be null");
    }

    /**
     * Retrieves an enriched page of contribution queue items for the admin inbox.
     *
     * @param filter filter and pagination criteria
     * @return enriched queue page DTO
     */
    public AdminWikiContributionQueuePageDTO getInboxPage(WikiContributionAdminFilter filter) {
        Objects.requireNonNull(filter, "Filter cannot be null");

        WikiContributionAdminPage rawPage = inboxUseCase.getInboxPage(filter);
        if (rawPage == null) {
            return AdminWikiContributionQueuePageDTO.empty(filter.page(), filter.size());
        }

        if (rawPage.items().isEmpty()) {
            return new AdminWikiContributionQueuePageDTO(
                    List.of(),
                    rawPage.page(),
                    rawPage.size(),
                    rawPage.totalElements(),
                    rawPage.totalPages(),
                    rawPage.first(),
                    rawPage.last()
            );
        }

        List<WikiContributionAdminItem> rawItems = rawPage.items();

        // 1. Collect unique contributor IDs for 1 bulk lookup to Identity
        Set<UUID> userIds = new HashSet<>();
        for (WikiContributionAdminItem item : rawItems) {
            if (item.submittedByUserId() != null) {
                userIds.add(item.submittedByUserId());
            }
        }

        Map<UUID, UserPublicProfileDTO> profileMap = userIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(userIds), Map.of());

        // 2. Compose enriched queue items
        List<AdminWikiContributionQueueItemDTO> enrichedItems = new ArrayList<>(rawItems.size());
        for (WikiContributionAdminItem item : rawItems) {
            AdminWikiContributionContributorDTO contributor = mapContributor(item.submittedByUserId(), profileMap);
            enrichedItems.add(new AdminWikiContributionQueueItemDTO(item, contributor));
        }

        return new AdminWikiContributionQueuePageDTO(
                enrichedItems,
                rawPage.page(),
                rawPage.size(),
                rawPage.totalElements(),
                rawPage.totalPages(),
                rawPage.first(),
                rawPage.last()
        );
    }

    /**
     * Retrieves the full detail representation of a contribution for inspection.
     *
     * @param contributionId ID of the contribution
     * @return enriched AdminWikiContributionDetailDTO
     */
    public AdminWikiContributionDetailDTO getDetail(UUID contributionId) {
        Objects.requireNonNull(contributionId, "contributionId cannot be null");

        WikiContributionAdminDetail detail = detailUseCase.execute(contributionId);

        Set<UUID> userIds = new HashSet<>();
        if (detail.submittedByUserId() != null) {
            userIds.add(detail.submittedByUserId());
        }
        if (detail.resolvedByUserId() != null) {
            userIds.add(detail.resolvedByUserId());
        }

        Map<UUID, UserPublicProfileDTO> profileMap = userIds.isEmpty()
                ? Map.of()
                : Objects.requireNonNullElse(userIdentityContract.findPublicProfilesByIds(userIds), Map.of());

        AdminWikiContributionContributorDTO contributor = mapContributor(detail.submittedByUserId(), profileMap);
        AdminWikiContributionContributorDTO resolver = detail.resolvedByUserId() != null
                ? mapContributor(detail.resolvedByUserId(), profileMap)
                : null;

        List<AdminWikiContributionSourceDTO> sources = detail.sources().stream()
                .map(AdminWikiContributionSourceDTO::from)
                .toList();

        return new AdminWikiContributionDetailDTO(
                detail.contributionId(),
                detail.articleId(),
                detail.articleTypeSnapshot(),
                detail.articleTitleSnapshot(),
                detail.articleSlugSnapshot(),
                detail.articleContentVersion(),
                contributor,
                detail.contextType(),
                detail.contributionType(),
                detail.message(),
                detail.selectedText(),
                detail.selectedPrefix(),
                detail.selectedSuffix(),
                detail.selectedHeadingAnchor(),
                detail.status(),
                detail.version(),
                detail.createdAt(),
                detail.updatedAt(),
                sources,
                detail.resolutionNote(),
                resolver,
                detail.resolvedAt(),
                detail.resolvedArticleContentVersion()
        );
    }

    private AdminWikiContributionContributorDTO mapContributor(UUID userId, Map<UUID, UserPublicProfileDTO> profileMap) {
        if (userId == null) {
            return AdminWikiContributionContributorDTO.unresolved(null);
        }
        UserPublicProfileDTO profile = profileMap.get(userId);
        if (profile != null) {
            return new AdminWikiContributionContributorDTO(
                    profile.userId(),
                    profile.displayName(),
                    profile.avatarUrl(),
                    true
            );
        }
        return AdminWikiContributionContributorDTO.unresolved(userId);
    }

    /**
     * Retrieves the count of contributions currently needing attention (status NEW).
     *
     * @return count of NEW contributions
     */
    public long getNewContributionCount() {
        return inboxUseCase.getCountByStatus(WikiContributionStatus.NEW);
    }
}
