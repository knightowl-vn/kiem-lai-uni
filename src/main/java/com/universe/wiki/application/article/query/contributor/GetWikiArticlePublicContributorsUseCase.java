package com.universe.wiki.application.article.query.contributor;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.ports.WikiArticlePublicContributorQueryPort;
import com.universe.wiki.contracts.dto.WikiPublicContributorDTO;
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
 *   <li>Single bulk lookup to UserIdentityContract when contributors exist.</li>
 *   <li>Omission of contributors whose public profile cannot be resolved.</li>
 *   <li>Zero leakage of technical UUIDs, moderation metadata, or reviewer IDs.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class GetWikiArticlePublicContributorsUseCase {

    private final WikiArticlePublicContributorQueryPort publicContributorQueryPort;
    private final UserIdentityContract userIdentityContract;

    public GetWikiArticlePublicContributorsUseCase(
            WikiArticlePublicContributorQueryPort publicContributorQueryPort,
            UserIdentityContract userIdentityContract
    ) {
        this.publicContributorQueryPort = Objects.requireNonNull(
                publicContributorQueryPort, "WikiArticlePublicContributorQueryPort không được để trống."
        );
        this.userIdentityContract = userIdentityContract;
    }

    public List<WikiPublicContributorDTO> execute(UUID articleId) {
        if (articleId == null) {
            return List.of();
        }

        List<WikiArticlePublicContributorAggregate> aggregates =
                publicContributorQueryPort.findActiveContributorsByArticleId(articleId);

        if (aggregates == null || aggregates.isEmpty() || userIdentityContract == null) {
            return List.of();
        }

        Set<UUID> userIds = aggregates.stream()
                .map(WikiArticlePublicContributorAggregate::contributorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (userIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, UserPublicProfileDTO> profileMap;
        try {
            profileMap = userIdentityContract.findPublicProfilesByIds(userIds);
        } catch (Exception ignored) {
            return List.of();
        }

        if (profileMap == null || profileMap.isEmpty()) {
            return List.of();
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
            }
        }

        return Collections.unmodifiableList(results);
    }
}
