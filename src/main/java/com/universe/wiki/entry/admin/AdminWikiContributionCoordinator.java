package com.universe.wiki.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.contribution.query.GetWikiContributionAdminInboxUseCase;
import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.application.contribution.query.WikiContributionAdminItem;
import com.universe.wiki.application.contribution.query.WikiContributionAdminPage;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionContributorDTO;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueueItemDTO;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Entry coordinator for the Admin Wiki Contribution Inbox.
 *
 * <p>Enforces bounded-context isolation and N+1 query prevention by performing at most
 * 1 bulk query to {@link UserIdentityContract} for contributor public profiles per page.
 */
@Service
public class AdminWikiContributionCoordinator {

    private final GetWikiContributionAdminInboxUseCase useCase;
    private final UserIdentityContract userIdentityContract;

    public AdminWikiContributionCoordinator(
            GetWikiContributionAdminInboxUseCase useCase,
            UserIdentityContract userIdentityContract
    ) {
        this.useCase = Objects.requireNonNull(useCase, "GetWikiContributionAdminInboxUseCase cannot be null");
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

        WikiContributionAdminPage rawPage = useCase.getInboxPage(filter);
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
            UserPublicProfileDTO profile = profileMap.get(item.submittedByUserId());
            AdminWikiContributionContributorDTO contributor;
            if (profile != null) {
                contributor = new AdminWikiContributionContributorDTO(
                        profile.userId(),
                        profile.displayName(),
                        profile.avatarUrl(),
                        true
                );
            } else {
                contributor = AdminWikiContributionContributorDTO.unresolved(item.submittedByUserId());
            }
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
     * Retrieves the count of contributions currently needing attention (status NEW).
     *
     * @return count of NEW contributions
     */
    public long getNewContributionCount() {
        return useCase.getCountByStatus(WikiContributionStatus.NEW);
    }
}
