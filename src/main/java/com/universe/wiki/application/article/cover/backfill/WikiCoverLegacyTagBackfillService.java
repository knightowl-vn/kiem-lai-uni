package com.universe.wiki.application.article.cover.backfill;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.domain.ClientTagConflictException;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.infrastructure.maintenance.WikiCoverLegacyTagBackfillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for safely backfilling canonical Media client tags onto legacy
 * Media assets that are proven to be currently referenced as Wiki article covers (MS-05G8C6).
 *
 * <p>Ownership proof boundary:
 * A Media asset is eligible for this backfill if and only if authoritative Wiki data
 * observes at least one current {@code wiki_articles.cover_media_asset_id} reference during this run.
 * Historical unreferenced assets with {@code client_tag = NULL} are strictly left untouched.
 *
 * <p>Keyset scan boundary:
 * Captures a single fixed upper bound {@code MAX(cover_media_asset_id)} at run start to guarantee
 * a finite, deterministic scan range with no OFFSET starvation.
 */
@Service
public class WikiCoverLegacyTagBackfillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiCoverLegacyTagBackfillService.class);

    private final MediaContract mediaContract;
    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final WikiCoverLegacyTagBackfillProperties properties;

    public WikiCoverLegacyTagBackfillService(
            MediaContract mediaContract,
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiCoverLegacyTagBackfillProperties properties
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.articleRepositoryPort = Objects.requireNonNull(articleRepositoryPort, "WikiArticleRepositoryPort cannot be null.");
        this.properties = Objects.requireNonNull(properties, "WikiCoverLegacyTagBackfillProperties cannot be null.");
    }

    /**
     * Executes legacy cover tag backfill using the configured page size.
     *
     * @return summary result record
     */
    public WikiCoverLegacyTagBackfillResult backfillLegacyCoverTags() {
        return backfillLegacyCoverTags(properties.getPageSize());
    }

    /**
     * Executes legacy cover tag backfill using a specified page size.
     *
     * @param pageSize number of distinct cover asset IDs per keyset page
     * @return summary result record
     */
    public WikiCoverLegacyTagBackfillResult backfillLegacyCoverTags(int pageSize) {
        if (pageSize <= 0 || pageSize > WikiReferencedCoverKeysetQuery.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and " + WikiReferencedCoverKeysetQuery.MAX_PAGE_SIZE + ", but found: " + pageSize
            );
        }

        Optional<String> maxAssetIdOpt = articleRepositoryPort.findMaxCoverMediaAssetId();
        if (maxAssetIdOpt.isEmpty()) {
            LOGGER.info("Wiki cover legacy client-tag backfill: no referenced covers found. Zero work required.");
            return WikiCoverLegacyTagBackfillResult.empty();
        }

        String runUpperBound = maxAssetIdOpt.get();
        LOGGER.info("Starting Wiki cover legacy client-tag backfill. Fixed runUpperBound: {}, PageSize: {}",
                runUpperBound, pageSize);

        int scannedAssets = 0;
        int successfulAssignmentsOrAlreadyTagged = 0;
        int conflicts = 0;
        int missingMediaAssets = 0;
        int failedAssets = 0;

        String lastAssetId = null;

        while (true) {
            WikiReferencedCoverKeysetQuery query = (lastAssetId == null)
                    ? WikiReferencedCoverKeysetQuery.firstPage(runUpperBound, pageSize)
                    : WikiReferencedCoverKeysetQuery.nextPage(lastAssetId, runUpperBound, pageSize);

            List<String> page = articleRepositoryPort.findDistinctCoverMediaAssetIdsKeyset(query);
            if (page.isEmpty()) {
                break;
            }

            for (String assetIdStr : page) {
                scannedAssets++;

                UUID assetId;
                try {
                    assetId = UUID.fromString(assetIdStr);
                } catch (Exception ex) {
                    LOGGER.error("Invalid UUID format for Wiki cover media asset ID: [{}]", assetIdStr, ex);
                    failedAssets++;
                    continue;
                }

                try {
                    mediaContract.assignClientTagIfAbsent(
                            assetId,
                            WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG
                    );
                    successfulAssignmentsOrAlreadyTagged++;
                } catch (ClientTagConflictException ex) {
                    LOGGER.warn("Client tag conflict on Media asset [{}]: existing tag [{}], requested [{}]",
                            assetId, ex.getExistingTag(), ex.getRequestedTag());
                    conflicts++;
                } catch (MediaAssetNotFoundException ex) {
                    LOGGER.warn("Media asset not found for referenced Wiki cover ID [{}]: {}",
                            assetId, ex.getMessage());
                    missingMediaAssets++;
                } catch (RuntimeException ex) {
                    LOGGER.error("Unexpected failure assigning client tag to Media asset [{}]",
                            assetId, ex);
                    failedAssets++;
                }
            }

            if (page.size() < pageSize) {
                break;
            }

            lastAssetId = page.get(page.size() - 1);
        }

        WikiCoverLegacyTagBackfillResult result = new WikiCoverLegacyTagBackfillResult(
                scannedAssets,
                successfulAssignmentsOrAlreadyTagged,
                conflicts,
                missingMediaAssets,
                failedAssets
        );

        LOGGER.info(
                "Completed Wiki cover legacy client-tag backfill. Scanned: {}, Successful/AlreadyTagged: {}, Conflicts: {}, Missing: {}, Failed: {}",
                result.scannedAssets(),
                result.successfulAssignmentsOrAlreadyTagged(),
                result.conflicts(),
                result.missingMediaAssets(),
                result.failedAssets()
        );

        return result;
    }
}
