package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.wiki.application.contribution.ListUserWikiContributionsUseCase;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWikiContributionController Tests")
class UserWikiContributionControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String USER_EMAIL = "contributor@universe.local";

    @Mock
    private ListUserWikiContributionsUseCase listUserWikiContributionsUseCase;

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private ArticleTypePathMapper articleTypePathMapper;
    private UserWikiContributionController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        articleTypePathMapper = new ArticleTypePathMapper();
        controller = new UserWikiContributionController(
                listUserWikiContributionsUseCase,
                wikiArticleQueryPort,
                articleTypePathMapper
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthenticatedRequestIdentityTestSupport.attach(
                request,
                new AuthenticatedRequestIdentity(
                        USER_ID,
                        USER_EMAIL,
                        "Contributor User",
                        null,
                        UserStatus.ACTIVE,
                        UserRole.USER
                )
        );
        return request;
    }

    @Test
    @DisplayName("GET /wiki/contributions khi chưa đăng nhập chuyển hướng về /login")
    void unauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/wiki/contributions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(listUserWikiContributionsUseCase);
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Bài viết PUBLISHED và đổi slug: link sử dụng slug trực tiếp (live), snapshot lịch sử được giữ nguyên")
    void publishedArticleWithChangedSlugUsesLiveNavigation() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();
        UUID articleId = UUID.randomUUID();

        UserWikiContributionItemDTO contribution = new UserWikiContributionItemDTO(
                UUID.randomUUID(),
                articleId,
                "Trần Bình An (Cũ)",
                "tran-binh-an-cu",
                "CHARACTER",
                "FULL_ARTICLE",
                "INCORRECT_INFORMATION",
                "Đề xuất sửa nội dung",
                "NEW",
                Instant.now(),
                null,
                null
        );

        UserWikiContributionPageDTO pageDto = new UserWikiContributionPageDTO(
                List.of(contribution),
                0,
                20,
                1L,
                1,
                true,
                true
        );

        WikiArticleListItemDTO liveArticle = new WikiArticleListItemDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an-moi",
                "CHARACTER",
                "PUBLISHED",
                USER_ID,
                Instant.now(),
                Instant.now(),
                2L
        );

        when(listUserWikiContributionsUseCase.execute(USER_ID, 0, 20)).thenReturn(pageDto);
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of(articleId, liveArticle));

        String viewName = controller.userContributionsPage(0, request, model);

        assertThat(viewName).isEqualTo("wiki/public/contributions");
        UserWikiContributionPageViewModel pageVm = (UserWikiContributionPageViewModel) model.getAttribute("contributionsPage");
        assertThat(pageVm).isNotNull();
        assertThat(pageVm.items()).hasSize(1);

        UserWikiContributionViewItem item = pageVm.items().get(0);
        // Snapshot preservation
        assertThat(item.articleTitleSnapshot()).isEqualTo("Trần Bình An (Cũ)");
        assertThat(item.articleSlugSnapshot()).isEqualTo("tran-binh-an-cu");
        // Safe live navigation
        assertThat(item.hasArticleLink()).isTrue();
        assertThat(item.liveArticleSlug()).isEqualTo("tran-binh-an-moi");
        assertThat(item.liveArticleTypePath()).isEqualTo("character");

        verify(wikiArticleQueryPort, times(1)).findListItemsByIds(Set.of(articleId));
    }

    @Test
    @DisplayName("Bài viết DRAFT hoặc ARCHIVED (không public): đóng góp vẫn hiển thị nhưng không tạo link")
    void nonPublicArticleShowsContributionWithoutLink() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();
        UUID articleId = UUID.randomUUID();

        UserWikiContributionItemDTO contribution = new UserWikiContributionItemDTO(
                UUID.randomUUID(),
                articleId,
                "Bí ẩn Thập Nhị Lâu",
                "bi-an-thap-nhi-lau",
                "LOCATION",
                "FULL_ARTICLE",
                "MISSING_INFORMATION",
                "Cần bổ sung chi tiết",
                "REVIEWING",
                Instant.now(),
                null,
                null
        );

        UserWikiContributionPageDTO pageDto = new UserWikiContributionPageDTO(
                List.of(contribution),
                0,
                20,
                1L,
                1,
                true,
                true
        );

        WikiArticleListItemDTO draftArticle = new WikiArticleListItemDTO(
                articleId,
                "Bí ẩn Thập Nhị Lâu",
                "bi-an-thap-nhi-lau",
                "LOCATION",
                "DRAFT",
                USER_ID,
                Instant.now(),
                Instant.now(),
                1L
        );

        when(listUserWikiContributionsUseCase.execute(USER_ID, 0, 20)).thenReturn(pageDto);
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of(articleId, draftArticle));

        controller.userContributionsPage(0, request, model);

        UserWikiContributionPageViewModel pageVm = (UserWikiContributionPageViewModel) model.getAttribute("contributionsPage");
        assertThat(pageVm).isNotNull();
        assertThat(pageVm.items()).hasSize(1);

        UserWikiContributionViewItem item = pageVm.items().get(0);
        assertThat(item.articleTitleSnapshot()).isEqualTo("Bí ẩn Thập Nhị Lâu");
        assertThat(item.hasArticleLink()).isFalse();
        assertThat(item.liveArticleSlug()).isNull();
        assertThat(item.liveArticleTypePath()).isNull();
    }

    @Test
    @DisplayName("Bài viết bị xóa hoặc không tìm thấy: đóng góp vẫn hiển thị snapshot và không tạo link")
    void missingArticleShowsContributionWithoutLink() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();
        UUID articleId = UUID.randomUUID();

        UserWikiContributionItemDTO contribution = new UserWikiContributionItemDTO(
                UUID.randomUUID(),
                articleId,
                "Bài viết cũ đã xóa",
                "bai-viet-cu-da-xoa",
                "ITEM",
                "FULL_ARTICLE",
                "OTHER",
                "Nội dung góp ý",
                "RESOLVED",
                Instant.now(),
                "Đã xử lý",
                Instant.now()
        );

        UserWikiContributionPageDTO pageDto = new UserWikiContributionPageDTO(
                List.of(contribution),
                0,
                20,
                1L,
                1,
                true,
                true
        );

        when(listUserWikiContributionsUseCase.execute(USER_ID, 0, 20)).thenReturn(pageDto);
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of());

        controller.userContributionsPage(0, request, model);

        UserWikiContributionPageViewModel pageVm = (UserWikiContributionPageViewModel) model.getAttribute("contributionsPage");
        assertThat(pageVm).isNotNull();
        assertThat(pageVm.items()).hasSize(1);

        UserWikiContributionViewItem item = pageVm.items().get(0);
        assertThat(item.articleTitleSnapshot()).isEqualTo("Bài viết cũ đã xóa");
        assertThat(item.hasArticleLink()).isFalse();
        assertThat(item.resolutionNote()).isEqualTo("Đã xử lý");
    }

    @Test
    @DisplayName("Batch resolution: tra cứu duy nhất 1 lần cho tập hợp các ID bài viết phân biệt (không N+1)")
    void batchResolvesMultipleArticlesInSingleQuery() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();
        UUID articleId1 = UUID.randomUUID();
        UUID articleId2 = UUID.randomUUID();

        UserWikiContributionItemDTO c1 = new UserWikiContributionItemDTO(
                UUID.randomUUID(), articleId1, "Bài 1", "bai-1", "CHARACTER", "FULL_ARTICLE", "WORDING", "msg1", "NEW", Instant.now(), null, null
        );
        UserWikiContributionItemDTO c2 = new UserWikiContributionItemDTO(
                UUID.randomUUID(), articleId2, "Bài 2", "bai-2", "LOCATION", "FULL_ARTICLE", "WORDING", "msg2", "NEW", Instant.now(), null, null
        );
        UserWikiContributionItemDTO c3 = new UserWikiContributionItemDTO(
                UUID.randomUUID(), articleId1, "Bài 1", "bai-1", "CHARACTER", "FULL_ARTICLE", "WORDING", "msg3", "NEW", Instant.now(), null, null
        );

        UserWikiContributionPageDTO pageDto = new UserWikiContributionPageDTO(
                List.of(c1, c2, c3), 0, 20, 3L, 1, true, true
        );

        when(listUserWikiContributionsUseCase.execute(USER_ID, 0, 20)).thenReturn(pageDto);
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId1, articleId2))).thenReturn(Map.of());

        controller.userContributionsPage(0, request, model);

        verify(wikiArticleQueryPort, times(1)).findListItemsByIds(Set.of(articleId1, articleId2));
    }

    @Test
    @DisplayName("GET /wiki/contributions chuẩn hóa page âm về 0")
    void normalizesNegativePage() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();

        when(listUserWikiContributionsUseCase.execute(USER_ID, 0, 20))
                .thenReturn(new UserWikiContributionPageDTO(List.of(), 0, 20, 0L, 0, true, true));

        String viewName = controller.userContributionsPage(-3, request, model);

        assertThat(viewName).isEqualTo("wiki/public/contributions");
        verify(listUserWikiContributionsUseCase).execute(USER_ID, 0, 20);
    }
}
