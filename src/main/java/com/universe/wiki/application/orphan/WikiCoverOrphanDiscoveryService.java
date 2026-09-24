package com.universe.wiki.application.orphan;

import com.universe.media.contracts.dto.FindActiveMediaAssetsKeysetQuery;
import com.universe.media.contracts.dto.MediaAssetCandidateDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.cover.WikiCoverMediaCoordinator;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort.ClearReferencedOrphanResult;
import com.universe.wiki.infrastructure.maintenance.WikiCoverOrphanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service for discovering unreferenced Wiki cover Media assets using
 * fixed-upper-bound keyset pagination over the opaque client tag "wiki.article.cover" (MS-05G8C5).
 */
@Service
public class WikiCoverOrphanDiscoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiCoverOrphanDiscoveryService.class);

    private final MediaContract mediaContract;
    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final WikiCoverOrphanRepositoryPort orphanRepositoryPort;
    private final ClockPort clockPort;
    private final WikiCoverOrphanProperties properties;

    public WikiCoverOrphanDiscoveryService(
            MediaContract mediaContract,
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiCoverOrphanRepositoryPort orphanRepositoryPort,
            ClockPort clockPort,
            WikiCoverOrphanProperties properties
    ) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
        this.articleRepositoryPort = Objects.requireNonNull(articleRepositoryPort, "WikiArticleRepositoryPort cannot be null.");
        this.orphanRepositoryPort = Objects.requireNonNull(orphanRepositoryPort, "WikiCoverOrphanRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.properties = Objects.requireNonNull(properties, "WikiCoverOrphanProperties cannot be null.");
    }

    /**
     * Executes a complete discovery run over tagged Media assets with a single fixed upper bound.
     *
     * @return summary metrics of the discovery run
     */
    public WikiCoverOrphanDiscoveryResult discoverOrphans() {
        Instant runNow = clockPort.now();
        Instant discoveryUpperBound = runNow.minus(properties.getDiscoverySafetyWindow());
        int pageSize = properties.getDiscoveryPageSize();
        String clientTag = WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG;

        LOGGER.info(
                "Starting Wiki cover orphan discovery scan. Tag: '{}', UpperBound: {}, PageSize: {}",
                clientTag,
                discoveryUpperBound,
                pageSize
        );

        int scannedCandidates = 0;
        int observedOrphans = 0;
        int activeReferenced = 0;
        int failedInspections = 0;
        int deletingPreserved = 0;

        Instant lastCreatedAt = null;
        UUID lastAssetId = null;

        while (true) {
            FindActiveMediaAssetsKeysetQuery query;
            if (lastCreatedAt == null) {
                query = FindActiveMediaAssetsKeysetQuery.firstPage(
                        clientTag,
                        discoveryUpperBound,
                        pageSize
                );
            } else {
                query = FindActiveMediaAssetsKeysetQuery.nextPage(
                        clientTag,
                        discoveryUpperBound,
                        lastCreatedAt,
                        lastAssetId,
                        pageSize
                );
            }

            List<MediaAssetCandidateDTO> candidates = mediaContract.findActiveAssetsByClientTagKeyset(query);
            if (candidates.isEmpty()) {
                break;
            }

            for (MediaAssetCandidateDTO candidate : candidates) {
                scannedCandidates++;
                try {
                    UUID assetId = candidate.assetId();
                    if (articleRepositoryPort.hasCoverReference(assetId)) {
                        activeReferenced++;
                        ClearReferencedOrphanResult clearResult =
                                orphanRepositoryPort.clearNonDeletingOrphanForReference(assetId);
                        if (clearResult == ClearReferencedOrphanResult.DELETING_PRESERVED) {
                            deletingPreserved++;
                            LOGGER.error(
                                    "INVARIANT BREACH: Media asset [{}] has an active Wiki cover reference but is in DELETING status in wiki_cover_orphans. Preserving DELETING fence.",
                                    assetId
                            );
                        }
                    } else {
                        observedOrphans++;
                        orphanRepositoryPort.recordOrphanObservation(assetId, clockPort.now());
                    }
                } catch (Exception ex) {
                    failedInspections++;
                    LOGGER.error(
                            "Failed to inspect cover reference for discovered Media asset [{}]: {}",
                            candidate.assetId(),
                            ex.getMessage(),
                            ex
                    );
                }
            }

            MediaAssetCandidateDTO lastCandidate = candidates.get(candidates.size() - 1);
            lastCreatedAt = lastCandidate.createdAt();
            lastAssetId = lastCandidate.assetId();

            if (candidates.size() < pageSize) {
                break;
            }
        }

        LOGGER.info(
                "Completed Wiki cover orphan discovery scan. Scanned: {}, Observed: {}, Referenced: {}, Failed: {}, DeletingPreserved: {}",
                scannedCandidates,
                observedOrphans,
                activeReferenced,
                failedInspections,
                deletingPreserved
        );

        return new WikiCoverOrphanDiscoveryResult(
                scannedCandidates,
                observedOrphans,
                activeReferenced,
                failedInspections,
                deletingPreserved
        );
    }
}
