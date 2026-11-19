package com.universe.wiki.application.article.query.published;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicWikiArticleDetailUseCase Unit Tests")
class GetPublicWikiArticleDetailUseCaseTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CREATOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EDITOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID COVER_ASSET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-02T12:00:00Z");

    @Mock
    private GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

    @Mock
    private IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;

    @Mock
    private WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort;

    @Mock
    private GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase;

    private GetPublicWikiArticleDetailUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        );
    }

    @Test
    @DisplayName("UseCase class is annotated with @Transactional(readOnly = true)")
    void classHasTransactionalReadOnlyAnnotation() {
        Transactional annotation = GetPublicWikiArticleDetailUseCase.class.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.readOnly()).isTrue();
    }

    @Test
    @DisplayName("Orchestrates guest CHARACTER detail read within single coordinator call and skips saved lookup")
    void orchestratesGuestCharacterDetailRead() {
        PublishedWikiArticleDTO article = createArticle(ArticleType.CHARACTER, COVER_ASSET_ID, 2L, CREATOR_ID, EDITOR_ID);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.CHARACTER, "tran-binh-an")))
                .thenReturn(article);

        String directCoverUrl = "https://res.cloudinary.com/demo/image/upload/w_300/tba.webp";
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.of(directCoverUrl));

        WikiAppreciationDetailState appreciationState =
                new WikiAppreciationDetailState(new BigDecimal("4.80"), 25L, null);
        when(getWikiAppreciationDetailStateUseCase.execute(ARTICLE_ID, null))
                .thenReturn(appreciationState);

        when(userIdentityContract.findPublicProfilesByIds(Set.of(EDITOR_ID)))
                .thenReturn(Map.of(EDITOR_ID, new UserPublicProfileDTO(EDITOR_ID, "Biên tập viên", null, "btv")));

        WikiPublicContributorsResult contributorsResult = new WikiPublicContributorsResult(
                List.of(new WikiPublicContributorDTO("Độc giả 1", "https://cdn.example/a.png", 3L)),
                false
        );
        when(getWikiArticlePublicContributorsUseCase.execute(ARTICLE_ID))
                .thenReturn(contributorsResult);

        PublicWikiArticleDetailResult result = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "tran-binh-an", null)
        );

        assertThat(result.article()).isSameAs(article);
        assertThat(result.saved()).isFalse();
        assertThat(result.directCoverUrl()).isEqualTo(directCoverUrl);
        assertThat(result.appreciationEligible()).isTrue();
        assertThat(result.appreciationState()).isSameAs(appreciationState);
        assertThat(result.attributionLine()).isEqualTo("Cập nhật bởi Biên tập viên");
        assertThat(result.publicContributorsResult()).isSameAs(contributorsResult);

        PublicWikiArticleDetailTimings timings = result.timings();
        assertThat(timings.authenticatedViewer()).isFalse();
        assertThat(timings.coverPresent()).isTrue();
        assertThat(timings.coverResolved()).isTrue();
        assertThat(timings.appreciationEligible()).isTrue();
        assertThat(timings.attributionResolved()).isTrue();
        assertThat(timings.contributorCount()).isEqualTo(1);
        assertThat(timings.totalCoordinatorNanos()).isGreaterThanOrEqualTo(0L);

        verifyNoInteractions(isWikiArticleSavedUseCase);
    }

    @Test
    @DisplayName("Orchestrates authenticated FACTION detail read with saved state and viewer rating isolation")
    void orchestratesAuthenticatedFactionDetailReadWithSavedAndRatingIsolation() {
        PublishedWikiArticleDTO article = createArticle(ArticleType.FACTION, null, 1L, CREATOR_ID, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.FACTION, "lac-phach-son")))
                .thenReturn(article);

        when(isWikiArticleSavedUseCase.execute(VIEWER_ID, ARTICLE_ID)).thenReturn(true);

        WikiAppreciationDetailState viewerAppreciationState =
                new WikiAppreciationDetailState(new BigDecimal("4.90"), 12L, new BigDecimal("5.0"));
        when(getWikiAppreciationDetailStateUseCase.execute(ARTICLE_ID, VIEWER_ID))
                .thenReturn(viewerAppreciationState);

        WikiAppreciationDetailState guestAppreciationState =
                new WikiAppreciationDetailState(new BigDecimal("4.90"), 12L, null);
        when(getWikiAppreciationDetailStateUseCase.execute(ARTICLE_ID, null))
                .thenReturn(guestAppreciationState);

        when(userIdentityContract.findPublicProfilesByIds(Set.of(CREATOR_ID)))
                .thenReturn(Map.of(CREATOR_ID, new UserPublicProfileDTO(CREATOR_ID, "Tác giả", null, "tac_gia")));

        when(getWikiArticlePublicContributorsUseCase.execute(ARTICLE_ID))
                .thenReturn(WikiPublicContributorsResult.empty());

        PublicWikiArticleDetailResult authenticatedResult = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.FACTION, "lac-phach-son", VIEWER_ID)
        );

        assertThat(authenticatedResult.saved()).isTrue();
        assertThat(authenticatedResult.appreciationEligible()).isTrue();
        assertThat(authenticatedResult.appreciationState().viewerValue()).isEqualByComparingTo("5.0");
        assertThat(authenticatedResult.attributionLine()).isEqualTo("Đăng bởi Tác giả");
        assertThat(authenticatedResult.timings().authenticatedViewer()).isTrue();
        assertThat(authenticatedResult.timings().coverPresent()).isFalse();

        // Guest execution for the same article must remain isolated (saved = false, viewerValue = null)
        PublicWikiArticleDetailResult guestResult = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.FACTION, "lac-phach-son", null)
        );

        assertThat(guestResult.saved()).isFalse();
        assertThat(guestResult.appreciationState().viewerValue()).isNull();
        verify(isWikiArticleSavedUseCase).execute(VIEWER_ID, ARTICLE_ID);
        verify(isWikiArticleSavedUseCase, never()).execute(null, ARTICLE_ID);
    }

    @Test
    @DisplayName("Skips appreciation query for ineligible article types (ITEM, REALM)")
    void skipsAppreciationForIneligibleArticleTypes() {
        PublishedWikiArticleDTO itemArticle = createArticle(ArticleType.ITEM, null, 1L, null, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.ITEM, "duong-kiem-ho")))
                .thenReturn(itemArticle);
        when(getWikiArticlePublicContributorsUseCase.execute(ARTICLE_ID))
                .thenReturn(WikiPublicContributorsResult.empty());

        PublicWikiArticleDetailResult result = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.ITEM, "duong-kiem-ho", VIEWER_ID)
        );

        assertThat(result.appreciationEligible()).isFalse();
        assertThat(result.appreciationState()).isNull();
        assertThat(result.timings().appreciationEligible()).isFalse();
        verifyNoInteractions(getWikiAppreciationDetailStateUseCase);
    }

    @Test
    @DisplayName("Handles missing cover asset ID or unavailable direct URL gracefully")
    void handlesCoverAbsentOrFallbackGracefully() {
        PublishedWikiArticleDTO noCoverArticle = createArticle(ArticleType.CHARACTER, null, 1L, null, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.CHARACTER, "no-cover")))
                .thenReturn(noCoverArticle);
        when(getWikiArticlePublicContributorsUseCase.execute(ARTICLE_ID))
                .thenReturn(WikiPublicContributorsResult.empty());

        PublicWikiArticleDetailResult noCoverResult = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "no-cover", null)
        );
        assertThat(noCoverResult.directCoverUrl()).isNull();
        assertThat(noCoverResult.timings().coverPresent()).isFalse();
        assertThat(noCoverResult.timings().coverResolved()).isFalse();
        verifyNoInteractions(wikiPublicCoverImageDeliveryPort);

        PublishedWikiArticleDTO fallbackCoverArticle = createArticle(ArticleType.CHARACTER, COVER_ASSET_ID, 1L, null, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.CHARACTER, "fallback")))
                .thenReturn(fallbackCoverArticle);
        when(wikiPublicCoverImageDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.empty());

        PublicWikiArticleDetailResult fallbackResult = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "fallback", null)
        );
        assertThat(fallbackResult.directCoverUrl()).isNull();
        assertThat(fallbackResult.timings().coverPresent()).isTrue();
        assertThat(fallbackResult.timings().coverResolved()).isFalse();
    }

    @Test
    @DisplayName("Handles missing, null, or blank attribution profiles cleanly and normalizes null contributors")
    void handlesAttributionMissingOrBlankProfilesGracefully() {
        PublishedWikiArticleDTO missingProfileArticle = createArticle(ArticleType.ITEM, null, 1L, CREATOR_ID, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.ITEM, "missing-profile")))
                .thenReturn(missingProfileArticle);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(CREATOR_ID)))
                .thenReturn(Map.of());
        when(getWikiArticlePublicContributorsUseCase.execute(ARTICLE_ID))
                .thenReturn(null);

        PublicWikiArticleDetailResult missingProfileResult = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.ITEM, "missing-profile", null)
        );
        assertThat(missingProfileResult.attributionLine()).isNull();
        assertThat(missingProfileResult.publicContributorsResult()).isEqualTo(WikiPublicContributorsResult.empty());

        // Blank display name -> null attribution
        doReturn(Map.of(CREATOR_ID, new UserPublicProfileDTO(CREATOR_ID, "   ", null, "blank")))
                .when(userIdentityContract).findPublicProfilesByIds(Set.of(CREATOR_ID));
        PublicWikiArticleDetailResult blankResult = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.ITEM, "missing-profile", null)
        );
        assertThat(blankResult.attributionLine()).isNull();

        // contentVersion > 1 with updatedBy == null -> null attribution (does not fall back to createdBy)
        PublishedWikiArticleDTO v2NoUpdaterArticle = createArticle(ArticleType.ITEM, null, 2L, CREATOR_ID, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.ITEM, "v2-no-upd")))
                .thenReturn(v2NoUpdaterArticle);
        PublicWikiArticleDetailResult v2Result = useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.ITEM, "v2-no-upd", null)
        );
        assertThat(v2Result.attributionLine()).isNull();
    }

    @Test
    @DisplayName("Propagates unchecked attribution Identity failure cleanly without invoking contributors stage")
    void propagatesAttributionIdentityFailure() {
        PublishedWikiArticleDTO failingIdentityArticle = createArticle(ArticleType.ITEM, null, 1L, CREATOR_ID, null);
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.ITEM, "fail-id")))
                .thenReturn(failingIdentityArticle);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(CREATOR_ID)))
                .thenThrow(new IllegalStateException("Identity service unavailable"));

        assertThatThrownBy(() -> useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.ITEM, "fail-id", null)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Identity service unavailable");

        verifyNoInteractions(getWikiArticlePublicContributorsUseCase);
    }

    @Test
    @DisplayName("Propagates PublishedWikiArticleNotFoundException without invoking downstream reads")
    void propagatesMissingArticleException() {
        when(getPublishedArticleUseCase.execute(new GetPublishedWikiArticleQuery(ArticleType.CHARACTER, "missing")))
                .thenThrow(new PublishedWikiArticleNotFoundException(ArticleType.CHARACTER, "missing"));

        assertThatThrownBy(() -> useCase.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "missing", VIEWER_ID)
        )).isInstanceOf(PublishedWikiArticleNotFoundException.class);

        verifyNoInteractions(
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        );
    }

    @Test
    @DisplayName("Rejects null query")
    void rejectsNullQuery() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetPublicWikiArticleDetailQuery cannot be null.");
    }

    @Test
    @DisplayName("Constructor rejects null dependencies")
    void constructorRejectsNullDependencies() {
        assertThatThrownBy(() -> new GetPublicWikiArticleDetailUseCase(
                null,
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetPublishedWikiArticleUseCase cannot be null.");

        assertThatThrownBy(() -> new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                null,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("IsWikiArticleSavedUseCase cannot be null.");

        assertThatThrownBy(() -> new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                isWikiArticleSavedUseCase,
                null,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiPublicCoverImageDeliveryPort cannot be null.");

        assertThatThrownBy(() -> new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                null,
                userIdentityContract,
                getWikiArticlePublicContributorsUseCase
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetWikiAppreciationDetailStateUseCase cannot be null.");

        assertThatThrownBy(() -> new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                null,
                getWikiArticlePublicContributorsUseCase
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UserIdentityContract cannot be null.");

        assertThatThrownBy(() -> new GetPublicWikiArticleDetailUseCase(
                getPublishedArticleUseCase,
                isWikiArticleSavedUseCase,
                wikiPublicCoverImageDeliveryPort,
                getWikiAppreciationDetailStateUseCase,
                userIdentityContract,
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("GetWikiArticlePublicContributorsUseCase cannot be null.");
    }

    private PublishedWikiArticleDTO createArticle(
            ArticleType type,
            UUID coverMediaAssetId,
            long contentVersion,
            UUID createdBy,
            UUID updatedBy
    ) {
        return new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                type.name(),
                "Tóm tắt",
                "## Nội dung\nChi tiết bài viết.",
                PUBLISHED_AT,
                UPDATED_AT,
                coverMediaAssetId,
                50,
                50,
                contentVersion,
                createdBy,
                updatedBy
        );
    }
}
