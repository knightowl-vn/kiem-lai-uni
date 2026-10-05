package com.universe.interaction.entry.web.personal;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.interaction.application.query.ListUserAuthoredCommentsUseCase;
import com.universe.interaction.application.query.UserCommentContextFilter;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAuthoredCommentController Tests")
class UserAuthoredCommentControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String USER_EMAIL = "commenter@universe.local";
    private static final UUID CHAPTER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMENT_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID COMMENT_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    @Mock
    private ListUserAuthoredCommentsUseCase listUserAuthoredCommentsUseCase;

    @Mock
    private ChapterListQueryPort chapterListQueryPort;

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private ArticleTypePathMapper articleTypePathMapper;
    private UserAuthoredCommentController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        articleTypePathMapper = new ArticleTypePathMapper();
        controller = new UserAuthoredCommentController(
                listUserAuthoredCommentsUseCase,
                chapterListQueryPort,
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
                        "Author User",
                        null,
                        "author_user",
                        UserStatus.ACTIVE,
                        UserRole.USER
                )
        );
        return request;
    }

    @Test
    @DisplayName("Anonymous access redirects to /login")
    void shouldRedirectAnonymousUserToLogin() throws Exception {
        mockMvc.perform(get("/comments/my"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verifyNoInteractions(listUserAuthoredCommentsUseCase, chapterListQueryPort, wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Authenticated user gets comments page and resolves both Novel and Wiki targets in single batch each")
    void shouldRenderCommentsPageAndBatchResolveMixedTargets() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();

        AuthoredCommentItemDTO itemNovel = new AuthoredCommentItemDTO(
                COMMENT_1_ID,
                CommentTargetType.NOVEL_CHAPTER,
                CHAPTER_ID,
                "Bình luận chương 1",
                NOW,
                NOW,
                null,
                null
        );

        AuthoredCommentItemDTO itemWiki = new AuthoredCommentItemDTO(
                COMMENT_2_ID,
                CommentTargetType.WIKI_ARTICLE,
                ARTICLE_ID,
                "Phản hồi nhân vật Trần Bình An",
                NOW.minusSeconds(120),
                NOW.minusSeconds(120),
                COMMENT_1_ID,
                COMMENT_1_ID
        );

        AuthoredCommentPageDTO pageDto = new AuthoredCommentPageDTO(
                List.of(itemNovel, itemWiki),
                0,
                20,
                2L,
                1,
                true,
                true
        );

        when(listUserAuthoredCommentsUseCase.execute(USER_ID, UserCommentContextFilter.ALL, 0, 20))
                .thenReturn(pageDto);

        when(chapterListQueryPort.findListItemsByIds(Set.of(CHAPTER_ID)))
                .thenReturn(Map.of(
                        CHAPTER_ID,
                        new ChapterListItemDTO(CHAPTER_ID, 1, "Kinh Trập", "kinh-trap", "PUBLISHED", NOW)
                ));

        when(wikiArticleQueryPort.findListItemsByIds(Set.of(ARTICLE_ID)))
                .thenReturn(Map.of(
                        ARTICLE_ID,
                        new WikiArticleListItemDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED", USER_ID, NOW, NOW, 1L)
                ));

        String view = controller.myCommentsPage("all", 0, request, model);

        assertThat(view).isEqualTo("interaction/personal/comments");
        assertThat(model.getAttribute("pageTitle")).isEqualTo("Bình luận của tôi");
        assertThat(model.getAttribute("activeFilter")).isEqualTo("all");

        UserAuthoredCommentPageViewModel pageVm =
                (UserAuthoredCommentPageViewModel) model.getAttribute("commentsPage");
        assertThat(pageVm).isNotNull();
        assertThat(pageVm.items()).hasSize(2);

        // 1. Novel Item Verification
        UserAuthoredCommentViewItem novelVm = pageVm.items().get(0);
        assertThat(novelVm.commentId()).isEqualTo(COMMENT_1_ID);
        assertThat(novelVm.contextLabel()).isEqualTo("Novel");
        assertThat(novelVm.typeLabel()).isEqualTo("Bình luận");
        assertThat(novelVm.isRoot()).isTrue();
        assertThat(novelVm.targetAvailable()).isTrue();
        assertThat(novelVm.targetTitle()).isEqualTo("Chương 1: Kinh Trập");
        assertThat(novelVm.contextUrl()).isEqualTo("/novel/chapters/kinh-trap?commentId=" + COMMENT_1_ID + "&threadId=" + COMMENT_1_ID + "#novelChapterComments");

        // 2. Wiki Item Verification
        UserAuthoredCommentViewItem wikiVm = pageVm.items().get(1);
        assertThat(wikiVm.commentId()).isEqualTo(COMMENT_2_ID);
        assertThat(wikiVm.contextLabel()).isEqualTo("Wiki");
        assertThat(wikiVm.typeLabel()).isEqualTo("Phản hồi");
        assertThat(wikiVm.isReply()).isTrue();
        assertThat(wikiVm.targetAvailable()).isTrue();
        assertThat(wikiVm.targetTitle()).isEqualTo("Trần Bình An");
        assertThat(wikiVm.contextUrl()).isEqualTo("/wiki/character/tran-binh-an?commentId=" + COMMENT_2_ID + "&threadId=" + COMMENT_1_ID + "#wikiDiscussion");

        verify(chapterListQueryPort).findListItemsByIds(Set.of(CHAPTER_ID));
        verify(wikiArticleQueryPort).findListItemsByIds(Set.of(ARTICLE_ID));
    }

    @Test
    @DisplayName("Novel-only page does not execute Wiki batch lookup")
    void shouldNotInvokeWikiLookupWhenOnlyNovelCommentsPresent() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();

        AuthoredCommentItemDTO itemNovel = new AuthoredCommentItemDTO(
                COMMENT_1_ID,
                CommentTargetType.NOVEL_CHAPTER,
                CHAPTER_ID,
                "Bình luận chương",
                NOW,
                NOW,
                null,
                null
        );

        AuthoredCommentPageDTO pageDto = new AuthoredCommentPageDTO(
                List.of(itemNovel), 0, 20, 1L, 1, true, true
        );

        when(listUserAuthoredCommentsUseCase.execute(USER_ID, UserCommentContextFilter.NOVEL, 0, 20))
                .thenReturn(pageDto);

        when(chapterListQueryPort.findListItemsByIds(Set.of(CHAPTER_ID)))
                .thenReturn(Map.of(
                        CHAPTER_ID,
                        new ChapterListItemDTO(CHAPTER_ID, 1, "Kinh Trập", "kinh-trap", "PUBLISHED", NOW)
                ));

        String view = controller.myCommentsPage("novel", 0, request, model);

        assertThat(view).isEqualTo("interaction/personal/comments");
        verify(chapterListQueryPort).findListItemsByIds(Set.of(CHAPTER_ID));
        verify(wikiArticleQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Wiki-only page does not execute Novel batch lookup")
    void shouldNotInvokeNovelLookupWhenOnlyWikiCommentsPresent() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();

        AuthoredCommentItemDTO itemWiki = new AuthoredCommentItemDTO(
                COMMENT_2_ID,
                CommentTargetType.WIKI_ARTICLE,
                ARTICLE_ID,
                "Bình luận bài viết",
                NOW,
                NOW,
                null,
                null
        );

        AuthoredCommentPageDTO pageDto = new AuthoredCommentPageDTO(
                List.of(itemWiki), 0, 20, 1L, 1, true, true
        );

        when(listUserAuthoredCommentsUseCase.execute(USER_ID, UserCommentContextFilter.WIKI, 0, 20))
                .thenReturn(pageDto);

        when(wikiArticleQueryPort.findListItemsByIds(Set.of(ARTICLE_ID)))
                .thenReturn(Map.of(
                        ARTICLE_ID,
                        new WikiArticleListItemDTO(ARTICLE_ID, "Trần Bình An", "tran-binh-an", "CHARACTER", "PUBLISHED", USER_ID, NOW, NOW, 1L)
                ));

        String view = controller.myCommentsPage("wiki", 0, request, model);

        assertThat(view).isEqualTo("interaction/personal/comments");
        verify(wikiArticleQueryPort).findListItemsByIds(Set.of(ARTICLE_ID));
        verify(chapterListQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Unavailable target handles draft/deleted target gracefully without leaking metadata")
    void shouldHandleUnavailableTargetWithoutLeakingMetadata() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();

        AuthoredCommentItemDTO itemDraft = new AuthoredCommentItemDTO(
                COMMENT_1_ID,
                CommentTargetType.NOVEL_CHAPTER,
                CHAPTER_ID,
                "Bình luận chương nháp",
                NOW,
                NOW,
                null,
                null
        );

        AuthoredCommentPageDTO pageDto = new AuthoredCommentPageDTO(
                List.of(itemDraft), 0, 20, 1L, 1, true, true
        );

        when(listUserAuthoredCommentsUseCase.execute(USER_ID, UserCommentContextFilter.ALL, 0, 20))
                .thenReturn(pageDto);

        // Target returned as DRAFT
        when(chapterListQueryPort.findListItemsByIds(Set.of(CHAPTER_ID)))
                .thenReturn(Map.of(
                        CHAPTER_ID,
                        new ChapterListItemDTO(CHAPTER_ID, 2, "Bí mật", "bi-mat", "DRAFT", NOW)
                ));

        controller.myCommentsPage("all", 0, request, model);

        UserAuthoredCommentPageViewModel pageVm =
                (UserAuthoredCommentPageViewModel) model.getAttribute("commentsPage");
        assertThat(pageVm).isNotNull();
        UserAuthoredCommentViewItem viewItem = pageVm.items().get(0);

        assertThat(viewItem.targetAvailable()).isFalse();
        assertThat(viewItem.targetTitle()).isNull();
        assertThat(viewItem.contextUrl()).isNull();
        assertThat(viewItem.targetUnavailableLabel()).isEqualTo("Chương không còn khả dụng");
        assertThat(viewItem.body()).isEqualTo("Bình luận chương nháp");
    }

    @Test
    @DisplayName("Normalizes negative page parameter to 0")
    void shouldNormalizeNegativePageToZero() {
        MockHttpServletRequest request = authenticatedRequest();
        Model model = new ConcurrentModel();

        when(listUserAuthoredCommentsUseCase.execute(USER_ID, UserCommentContextFilter.ALL, 0, 20))
                .thenReturn(AuthoredCommentPageDTO.empty(0, 20));

        controller.myCommentsPage("all", -10, request, model);

        verify(listUserAuthoredCommentsUseCase).execute(USER_ID, UserCommentContextFilter.ALL, 0, 20);
    }
}
