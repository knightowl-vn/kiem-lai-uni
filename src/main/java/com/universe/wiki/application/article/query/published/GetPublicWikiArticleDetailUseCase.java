package com.universe.wiki.application.article.query.published;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;
import com.universe.wiki.domain.article.ArticleType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public Wiki article detail query coordinator.
 *
 * <p>Orchestrates reading the published Wiki article, viewer-specific saved state (when authenticated),
 * direct public cover image delivery URL, appreciation detail state (for CHARACTER and FACTION articles),
 * author/editor attribution display name, and public contributor recognition list within a single
 * Spring-managed read-only transaction boundary {@code @Transactional(readOnly = true)}.
 *
 * <p>Consolidating these read stages into a single shared transaction eliminates redundant JDBC
 * connection acquisitions and remote transaction-control round trips ({@code setAutoCommit},
 * {@code setReadOnly}, {@code commit}) while keeping HTTP model binding, CommonMark Markdown
 * rendering, and Thymeleaf view rendering outside the database transaction.
 */
@Service
@Transactional(readOnly = true)
public class GetPublicWikiArticleDetailUseCase {

    public static final int COVER_TARGET_WIDTH = 300;

    private final GetPublishedWikiArticleUseCase getPublishedArticleUseCase;
    private final IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;
    private final WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort;
    private final GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase;
    private final UserIdentityContract userIdentityContract;
    private final GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase;

    @Autowired
    public GetPublicWikiArticleDetailUseCase(
            GetPublishedWikiArticleUseCase getPublishedArticleUseCase,
            IsWikiArticleSavedUseCase isWikiArticleSavedUseCase,
            WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort,
            GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase,
            UserIdentityContract userIdentityContract,
            GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase
    ) {
        this.getPublishedArticleUseCase = Objects.requireNonNull(
                getPublishedArticleUseCase,
                "GetPublishedWikiArticleUseCase cannot be null."
        );
        this.isWikiArticleSavedUseCase = Objects.requireNonNull(
                isWikiArticleSavedUseCase,
                "IsWikiArticleSavedUseCase cannot be null."
        );
        this.wikiPublicCoverImageDeliveryPort = Objects.requireNonNull(
                wikiPublicCoverImageDeliveryPort,
                "WikiPublicCoverImageDeliveryPort cannot be null."
        );
        this.getWikiAppreciationDetailStateUseCase = Objects.requireNonNull(
                getWikiAppreciationDetailStateUseCase,
                "GetWikiAppreciationDetailStateUseCase cannot be null."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract cannot be null."
        );
        this.getWikiArticlePublicContributorsUseCase = Objects.requireNonNull(
                getWikiArticlePublicContributorsUseCase,
                "GetWikiArticlePublicContributorsUseCase cannot be null."
        );
    }

    public PublicWikiArticleDetailResult execute(GetPublicWikiArticleDetailQuery query) {
        Objects.requireNonNull(query, "GetPublicWikiArticleDetailQuery cannot be null.");

        long coordinatorStart = System.nanoTime();

        // Stage 1: Published article lookup
        long t0 = System.nanoTime();
        PublishedWikiArticleDTO article = getPublishedArticleUseCase.execute(
                new GetPublishedWikiArticleQuery(query.articleType(), query.slug())
        );
        long t1 = System.nanoTime();
        long articleQueryNanos = t1 - t0;

        // Stage 2: Viewer-specific saved state lookup (only when authenticated viewerUserId is present)
        UUID viewerUserId = query.viewerUserId();
        boolean isSaved = false;
        if (viewerUserId != null) {
            isSaved = isWikiArticleSavedUseCase.execute(viewerUserId, article.id());
        }
        long t2 = System.nanoTime();
        long savedStateQueryNanos = t2 - t1;

        // Stage 3: Public cover image direct delivery URL resolution
        boolean coverPresent = article.coverMediaAssetId() != null;
        String directCoverUrl = null;
        if (coverPresent) {
            Optional<String> resolvedCover = wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrl(
                    article.coverMediaAssetId(),
                    COVER_TARGET_WIDTH,
                    true
            );
            directCoverUrl = resolvedCover != null ? resolvedCover.orElse(null) : null;
        }
        long t3 = System.nanoTime();
        long coverResolveNanos = t3 - t2;

        // Stage 4: Appreciation detail state (only for CHARACTER and FACTION)
        ArticleType resolvedArticleType = query.articleType();
        boolean isAppreciationEligible = resolvedArticleType == ArticleType.CHARACTER
                || resolvedArticleType == ArticleType.FACTION;
        WikiAppreciationDetailState appreciationState = null;
        if (isAppreciationEligible) {
            appreciationState = getWikiAppreciationDetailStateUseCase.execute(article.id(), viewerUserId);
        }
        long t4 = System.nanoTime();
        long appreciationQueryNanos = t4 - t3;

        // Stage 5: Author / editor public attribution line
        String attributionLine = resolveAttributionLine(article);
        long t5 = System.nanoTime();
        long attributionQueryNanos = t5 - t4;

        // Stage 6: Public contributors recognition list
        WikiPublicContributorsResult contributorsResult =
                getWikiArticlePublicContributorsUseCase.execute(article.id());
        if (contributorsResult == null) {
            contributorsResult = WikiPublicContributorsResult.empty();
        }
        long t6 = System.nanoTime();
        long contributorsQueryNanos = t6 - t5;
        long totalCoordinatorNanos = t6 - coordinatorStart;

        PublicWikiArticleDetailTimings timings = new PublicWikiArticleDetailTimings(
                articleQueryNanos,
                savedStateQueryNanos,
                coverResolveNanos,
                appreciationQueryNanos,
                attributionQueryNanos,
                contributorsQueryNanos,
                totalCoordinatorNanos,
                viewerUserId != null,
                coverPresent,
                directCoverUrl != null,
                isAppreciationEligible,
                attributionLine != null,
                contributorsResult.contributors().size()
        );

        return new PublicWikiArticleDetailResult(
                article,
                isSaved,
                directCoverUrl,
                isAppreciationEligible,
                appreciationState,
                attributionLine,
                contributorsResult,
                timings
        );
    }

    private String resolveAttributionLine(PublishedWikiArticleDTO article) {
        if (article.contentVersion() > 1L && article.updatedBy() != null) {
            String displayName = resolveDisplayName(article.updatedBy());
            if (displayName != null && !displayName.isBlank()) {
                return "Cập nhật bởi " + displayName;
            }
        } else if (article.createdBy() != null) {
            String displayName = resolveDisplayName(article.createdBy());
            if (displayName != null && !displayName.isBlank()) {
                return "Đăng bởi " + displayName;
            }
        }
        return null;
    }

    private String resolveDisplayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        // Missing, inactive, or blank profiles return null safely within the shared read-only transaction.
        // Do not swallow unchecked exceptions from the transactional Identity service here: catching them
        // inside a participating REQUIRED boundary leaves the transaction marked rollback-only and causes
        // UnexpectedRollbackException on commit.
        Map<UUID, UserPublicProfileDTO> profiles =
                userIdentityContract.findPublicProfilesByIds(Set.of(userId));
        if (profiles == null) {
            return null;
        }
        UserPublicProfileDTO profile = profiles.get(userId);
        if (profile != null && profile.displayName() != null && !profile.displayName().isBlank()) {
            return profile.displayName();
        }
        return null;
    }
}
