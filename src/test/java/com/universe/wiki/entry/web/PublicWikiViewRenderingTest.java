package com.universe.wiki.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase;
import com.universe.wiki.application.article.query.published.GetPublicWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.published.GetPublicWikiArticleDetailUseCase;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleQuery;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleUseCase;
import com.universe.wiki.application.article.query.published.GetPublicWikiCatalogOverviewQuery;
import com.universe.wiki.application.article.query.published.GetPublicWikiCatalogOverviewUseCase;
import com.universe.wiki.application.article.query.published.PublicWikiArticleDetailResult;
import com.universe.wiki.application.article.query.published.PublicWikiArticleDetailTimings;
import com.universe.wiki.application.article.query.published.PublicWikiCatalogOverviewResult;
import com.universe.wiki.application.article.query.published.PublicWikiCatalogOverviewTimings;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesQuery;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesUseCase;
import com.universe.wiki.application.article.render.RenderedWikiContent;
import com.universe.wiki.application.article.render.WikiMarkdownRenderer;
import com.universe.wiki.application.article.render.WikiTocItem;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.contracts.dto.WikiPublicContributorsResult;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import com.universe.wiki.domain.article.ArticleType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicWikiController.class)
@Import({
        SecurityBeanConfig.class,
        AuthenticatedEmailResolver.class
})
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-wiki-rendering-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicWikiViewRendering Tests (Thymeleaf SSR)")
class PublicWikiViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

    @MockBean
    private GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

    @MockBean
    private ArticleTypePathMapper articleTypePathMapper;

    @MockBean
    private WikiMarkdownRenderer wikiMarkdownRenderer;

    @MockBean
    private IsWikiArticleSavedUseCase isWikiArticleSavedUseCase;

    @MockBean
    private GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase;

    @MockBean
    private GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private com.universe.identity.application.ports.CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase;

    @MockBean
    private WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort;

    @MockBean
    private GetPublicWikiCatalogOverviewUseCase getPublicWikiCatalogOverviewUseCase;

    @MockBean
    private GetPublicWikiArticleDetailUseCase getPublicWikiArticleDetailUseCase;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    private final Instant now = Instant.parse("2026-08-01T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());

        lenient().when(getWikiArticlePublicContributorsUseCase.execute(any()))
                .thenReturn(WikiPublicContributorsResult.empty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Public Wiki Index SSR: Renders direct Cloudinary cover URL when available and falls back to canonical /content")
    void shouldRenderPublicIndexWithDirectCoverAndFallback() throws Exception {
        UUID coverId1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID coverId2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String directUrl1 = "https://res.cloudinary.com/test-cloud/image/upload/w_300/wiki_cover1.webp";

        PublishedWikiArticleListItemDTO item1 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Trần Bình An", "tran-binh-an", "CHARACTER",
                "Nhân vật chính của Kiếm Lai.", now, now, coverId1, 20, 80
        );
        PublishedWikiArticleListItemDTO item2 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Lạc Phách Sơn", "lac-phach-son", "FACTION",
                "Tông môn của Trần Bình An.", now, now, coverId2, 50, 50
        );
        PublishedWikiArticleListItemDTO item3 = new PublishedWikiArticleListItemDTO(
                UUID.randomUUID(), "Dưỡng Kiếm Hồ", "duong-kiem-ho", "ITEM",
                "Bảo vật nuôi kiếm.", now, now, null, 50, 50
        );

        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item1, item2, item3), 0, 20, 3L, 1, true, true
        );

        PublicWikiCatalogOverviewResult overviewResult = new PublicWikiCatalogOverviewResult(
                pageDTO,
                Map.of(),
                Map.of(coverId1, directUrl1),
                PublicWikiCatalogOverviewTimings.empty()
        );
        when(getPublicWikiCatalogOverviewUseCase.execute(any(GetPublicWikiCatalogOverviewQuery.class)))
                .thenReturn(overviewResult);

        mockMvc.perform(get("/wiki"))
                .andExpect(status().isOk())
                // Index layout markers
                .andExpect(content().string(containsString("wiki-public-index-card-media")))
                // Card 1: Primary direct URL in src, canonical content in data-fallback-src, focal position
                .andExpect(content().string(containsString("Trần Bình An")))
                .andExpect(content().string(containsString(directUrl1)))
                .andExpect(content().string(containsString("/media/assets/" + coverId1 + "/content")))
                .andExpect(content().string(containsString("object-position: 20% 80%")))
                // Card 2: Missing direct URL -> uses canonical /content as primary src (never /variants/w300)
                .andExpect(content().string(containsString("Lạc Phách Sơn")))
                .andExpect(content().string(containsString("/media/assets/" + coverId2 + "/content")))
                // Never unverified /variants/w300
                .andExpect(content().string(not(containsString("/variants/w300"))))
                // Card 3: No cover -> renders placeholder SVG
                .andExpect(content().string(containsString("Dưỡng Kiếm Hồ")))
                .andExpect(content().string(containsString("wiki-public-index-card-placeholder")))
                // Onerror loop guard
                .andExpect(content().string(containsString("this.getAttribute('src') !== this.getAttribute('data-fallback-src')")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Public Wiki Detail SSR: Renders direct Cloudinary cover URL in header portrait with canonical fallback")
    void shouldRenderPublicDetailWithDirectCoverAndFallback() throws Exception {
        UUID coverId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String directDetailUrl = "https://res.cloudinary.com/test-cloud/image/upload/w_300/wiki_detail.webp";

        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                UUID.randomUUID(), "Trần Bình An", "tran-binh-an", "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "## Thân thế\nTrần Bình An sinh ra tại Ly Châu Động Thiên.",
                now, now, coverId, 30, 70, 1L, UUID.randomUUID(), null
        );

        when(articleTypePathMapper.fromPath("character")).thenReturn(ArticleType.CHARACTER);
        when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");
        when(getPublicWikiArticleDetailUseCase.execute(any(GetPublicWikiArticleDetailQuery.class)))
                .thenReturn(new PublicWikiArticleDetailResult(
                        article,
                        false,
                        directDetailUrl,
                        true,
                        null,
                        null,
                        WikiPublicContributorsResult.empty(),
                        PublicWikiArticleDetailTimings.empty()
                ));
        when(wikiMarkdownRenderer.render(any()))
                .thenReturn(new RenderedWikiContent("<h2 id=\"than-the\">Thân thế</h2><p>Trần Bình An sinh ra tại Ly Châu Động Thiên.</p>",
                        List.of(new WikiTocItem(2, "Thân thế", "than-the"))));

        mockMvc.perform(get("/wiki/character/tran-binh-an"))
                .andExpect(status().isOk())
                // Detail layout markers
                .andExpect(content().string(containsString("wiki-public-cover-wrapper")))
                .andExpect(content().string(containsString("wiki-public-cover-image")))
                // Direct URL as primary src
                .andExpect(content().string(containsString(directDetailUrl)))
                // Canonical /content as data-fallback-src
                .andExpect(content().string(containsString("/media/assets/" + coverId + "/content")))
                // Object-position
                .andExpect(content().string(containsString("object-position: 30% 70%")))
                // Never unverified /variants/w300
                .andExpect(content().string(not(containsString("/variants/w300"))))
                // Onerror loop guard
                .andExpect(content().string(containsString("this.getAttribute('src') !== this.getAttribute('data-fallback-src')")))
                // Content & title rendered
                .andExpect(content().string(containsString("Trần Bình An")))
                .andExpect(content().string(containsString("Thân thế")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Public Wiki Detail SSR: Falls back to canonical /content as primary when direct URL is absent")
    void shouldRenderPublicDetailWithCanonicalFallbackWhenDirectUrlAbsent() throws Exception {
        UUID coverId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        PublishedWikiArticleDTO article = new PublishedWikiArticleDTO(
                UUID.randomUUID(), "Lạc Phách Sơn", "lac-phach-son", "FACTION",
                "Tông môn của Trần Bình An.",
                "## Lịch sử\nTông môn được thành lập tại Bảo Bình Châu.",
                now, now, coverId, 40, 60, 1L, UUID.randomUUID(), null
        );

        when(articleTypePathMapper.fromPath("faction")).thenReturn(ArticleType.FACTION);
        when(articleTypePathMapper.toPath(ArticleType.FACTION)).thenReturn("faction");
        when(getPublicWikiArticleDetailUseCase.execute(any(GetPublicWikiArticleDetailQuery.class)))
                .thenReturn(new PublicWikiArticleDetailResult(
                        article,
                        false,
                        null,
                        true,
                        null,
                        null,
                        WikiPublicContributorsResult.empty(),
                        PublicWikiArticleDetailTimings.empty()
                ));
        when(wikiMarkdownRenderer.render(any()))
                .thenReturn(new RenderedWikiContent("<h2 id=\"lich-su\">Lịch sử</h2><p>Tông môn thành lập...</p>", List.of()));

        mockMvc.perform(get("/wiki/faction/lac-phach-son"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wiki-public-cover-wrapper")))
                // Primary src is canonical /content
                .andExpect(content().string(containsString("/media/assets/" + coverId + "/content")))
                // Never unverified /variants/w300
                .andExpect(content().string(not(containsString("/variants/w300"))))
                // Onerror loop guard
                .andExpect(content().string(containsString("this.getAttribute('src') !== this.getAttribute('data-fallback-src')")));
    }
}
