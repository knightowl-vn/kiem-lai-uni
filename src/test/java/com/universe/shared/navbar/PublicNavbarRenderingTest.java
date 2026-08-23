package com.universe.shared.navbar;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.entry.web.IdentityPageController;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.reader.GetReaderNovelLandingUseCase;
import com.universe.novel.contracts.dto.reader.ReaderNovelLandingDTO;
import com.universe.novel.contracts.dto.reader.ReaderNovelOverviewDTO;
import com.universe.novel.entry.reader.ReaderNovelPageController;
import com.universe.wiki.application.article.query.published.GetPublishedWikiArticleUseCase;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesQuery;
import com.universe.wiki.application.article.query.published.ListPublishedWikiArticlesUseCase;
import com.universe.wiki.application.article.render.WikiMarkdownRenderer;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.entry.web.PublicWikiController;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;
import com.universe.shared.security.AuthenticatedEmailResolver;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        IdentityPageController.class,
        PublicWikiController.class,
        ReaderNovelPageController.class
})
@Import({
        SecurityBeanConfig.class,
        AuthenticatedEmailResolver.class
})
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-for-navbar-unit-test-12345",
        "security.remember-me.secure-cookie=false"
})
class PublicNavbarRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase;

    @MockBean
    private GetPublishedWikiArticleUseCase getPublishedArticleUseCase;

    @MockBean
    private ArticleTypePathMapper articleTypePathMapper;

    @MockBean
    private WikiMarkdownRenderer wikiMarkdownRenderer;

    @MockBean
    private GetReaderNovelLandingUseCase getReaderNovelLandingUseCase;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Trang chủ /home render navbar với Home active, liên kết Novel, Wiki")
    void homePageRendersNavbarWithNovelAndWikiLinks() throws Exception {
        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/home\"")))
                .andExpect(content().string(containsString("navbar-home-link")))
                .andExpect(content().string(containsString("active")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString("href=\"/novel\"")))
                .andExpect(content().string(containsString("href=\"/wiki\"")))
                .andExpect(content().string(containsString("navbar-novel-link")))
                .andExpect(content().string(containsString("navbar-wiki-link")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Trang Wiki /wiki render navbar với Wiki active")
    void wikiPageRendersNavbarWithActiveWikiLink() throws Exception {
        when(listPublishedArticlesUseCase.execute(any(ListPublishedWikiArticlesQuery.class)))
                .thenReturn(new PublishedWikiArticlePageDTO(
                        List.of(),
                        0,
                        20,
                        0L,
                        0,
                        true,
                        true
                ));

        mockMvc.perform(get("/wiki"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/novel\"")))
                .andExpect(content().string(containsString("href=\"/wiki\"")))
                .andExpect(content().string(containsString("navbar-wiki-link")))
                .andExpect(content().string(containsString("active")))
                .andExpect(content().string(containsString("aria-current=\"page\"")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Trang Novel /novel render navbar với Novel active")
    void novelLandingPageRendersNavbarWithActiveNovelLink() throws Exception {
        when(getReaderNovelLandingUseCase.execute())
                .thenReturn(new ReaderNovelLandingDTO(
                        new ReaderNovelOverviewDTO(
                                "Kiếm Lai",
                                "kiem-lai",
                                "Phong Hỏa Hí Chư Hầu",
                                "Giới thiệu truyện Kiếm Lai",
                                null,
                                "ONGOING"
                        ),
                        List.of()
                ));

        mockMvc.perform(get("/novel"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/novel\"")))
                .andExpect(content().string(containsString("href=\"/wiki\"")))
                .andExpect(content().string(containsString("navbar-novel-link")))
                .andExpect(content().string(containsString("active")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString("class=\"novel-reader\"")));
    }
}
