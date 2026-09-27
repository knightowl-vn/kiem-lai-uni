package com.universe.wiki.application.article.query.contributor;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.ports.WikiArticlePublicContributorQueryPort;
import com.universe.wiki.contracts.dto.WikiPublicContributorDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use case to retrieve the public contributor recognition list for a published Wiki article.
 *
 * <p>Enforces:
 * <ol>
 *   <li>Only active contribution credits participate.</li>
 *   <li>Single bulk lookup to UserIdentityContract for up to 51 candidate contributors.</li>
 *   <li>Detection of candidate truncation (> 50 candidates in database).</li>
 *   <li>Omission of contributors whose public profile cannot be resolved.</li>
 *   <li>Rendering of at most 50 public contributors, utilizing candidate 51 to backfill if needed.</li>
 *   <li>Zero leakage of technical UUIDs, moderation metadata, or reviewer IDs.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class GetWikiArticlePublicContributorsUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetWikiArticlePublicContributorsUseCase.class);
    private static final int MAX_PUBLIC_CONTRIBUTORS = 50;

    private final WikiArticlePublicContributorQueryPort publicContributorQueryPort;
    private final UserIdentityContract userIdentityContract;

    public GetWikiArticlePublicContributorsUseCase(
            WikiArticlePublicContributorQueryPort publicContributorQueryPort,
            UserIdentityContract userIdentityContract
    ) {
        this.publicContributorQueryPort = Objects.requireNonNull(
                publicContributorQueryPort, "WikiArticlePublicContributorQueryPort không được để trống."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract, "UserIdentityContract không được để trống."
        );
    }

    public WikiPublicContributorsResult execute(UUID articleId) {
        if (articleId == null) {
            return WikiPublicContributorsResult.empty();
        }

        List<WikiArticlePublicContributorAggregate> aggregates =
                publicContributorQueryPort.findActiveContributorsByArticleId(articleId);

        if (aggregates == null || aggregates.isEmpty()) {
            return WikiPublicContributorsResult.empty();
        }

        boolean candidateLimitReached = aggregates.size() > MAX_PUBLIC_CONTRIBUTORS;

        Set<UUID> userIds = aggregates.stream()
                .map(WikiArticlePublicContributorAggregate::contributorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (userIds.isEmpty()) {
            return WikiPublicContributorsResult.empty();
        }

        Map<UUID, UserPublicProfileDTO> profileMap;
        try {
            profileMap = userIdentityContract.findPublicProfilesByIds(userIds);
        } catch (RuntimeException ex) {
            log.warn(
                    "Không thể tra cứu hồ sơ người đóng góp cho bài viết {} ({})",
                    articleId,
                    ex.getClass().getSimpleName()
            );
            return WikiPublicContributorsResult.empty();
        }

        if (profileMap == null || profileMap.isEmpty()) {
            return WikiPublicContributorsResult.empty();
        }

        List<WikiPublicContributorDTO> results = new ArrayList<>();
        for (WikiArticlePublicContributorAggregate aggregate : aggregates) {
            UserPublicProfileDTO profile = profileMap.get(aggregate.contributorUserId());
            if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
                results.add(new WikiPublicContributorDTO(
                        profile.displayName().trim(),
                        profile.avatarUrl(),
                        aggregate.activeCreditCount()
                ));
                if (results.size() == MAX_PUBLIC_CONTRIBUTORS) {
                    break;
                }
            }
        }

        return new WikiPublicContributorsResult(results, candidateLimitReached);
    }
}
