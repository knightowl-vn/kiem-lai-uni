package com.universe.interaction.entry.admin;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportContextNavigationDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportTargetDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportUserDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentReportDetailControllerTest {

    @Mock
    private AdminCommentReportDetailCoordinator detailCoordinator;

    @Mock
    private AdminCommentReportContextNavigationCoordinator navigationCoordinator;

    @Mock
    private ArticleTypePathMapper articleTypePathMapper;

    private AdminCommentReportDetailController controller;

    private final UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID threadId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID reporterId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID authorId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID targetId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new AdminCommentReportDetailController(detailCoordinator, navigationCoordinator, articleTypePathMapper);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor enforces non-null detail coordinator")
        void constructorEnforcesNonNullDetailCoordinator() {
            assertThatThrownBy(() -> new AdminCommentReportDetailController(null, navigationCoordinator, articleTypePathMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("AdminCommentReportDetailCoordinator cannot be null");
        }

        @Test
        @DisplayName("Constructor enforces non-null navigation coordinator")
        void constructorEnforcesNonNullNavigationCoordinator() {
            assertThatThrownBy(() -> new AdminCommentReportDetailController(detailCoordinator, null, articleTypePathMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("AdminCommentReportContextNavigationCoordinator cannot be null");
        }

        @Test
        @DisplayName("Constructor enforces non-null article type path mapper")
        void constructorEnforcesNonNullArticleTypePathMapper() {
            assertThatThrownBy(() -> new AdminCommentReportDetailController(detailCoordinator, navigationCoordinator, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ArticleTypePathMapper cannot be null");
        }
    }

    @Nested
    @DisplayName("GET /admin/comments/reports/{reportId} - Report Detail View")
    class ReportDetailTests {

        @Test
        @DisplayName("Case A: Success - coordinator called once, view name, model attributes, and no-cache headers verified")
        void shouldRenderDetailSuccessfully() {
            ExtendedModelMap model = new ExtendedModelMap();
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            AdminCommentReportDetailDTO mockDto = createSampleDetailDTO();
            when(detailCoordinator.getDetail(reportId)).thenReturn(mockDto);

            String view = controller.reportDetail(reportId, model, response, redirectAttributes);

            // 1. View name verification
            assertThat(view).isEqualTo("admin/comments/report-detail");

            // 2. Coordinator invocation verification
            verify(detailCoordinator, times(1)).getDetail(reportId);

            // 3. Model attribute verification
            assertThat(model.get("report")).isEqualTo(mockDto);
            assertThat(model.get("pageTitle")).isEqualTo("Chi tiết báo cáo bình luận");
            assertThat(model.get("activeMenu")).isEqualTo("comment-reports");

            // 4. HTTP no-cache headers verification
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
            assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(response.getDateHeader("Expires")).isEqualTo(0L);

            // 5. No redirect attributes on success
            assertThat(redirectAttributes.getFlashAttributes()).isEmpty();
        }

        @Test
        @DisplayName("Case B: Report not found - catches InteractionReportNotFoundException, adds flash errorMessage, redirects to /admin/comments/reports")
        void shouldRedirectToQueueWhenReportNotFound() {
            ExtendedModelMap model = new ExtendedModelMap();
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(detailCoordinator.getDetail(reportId)).thenThrow(new InteractionReportNotFoundException(reportId));

            String view = controller.reportDetail(reportId, model, response, redirectAttributes);

            // 1. Redirect view
            assertThat(view).isEqualTo("redirect:/admin/comments/reports");

            // 2. Exactly one coordinator call
            verify(detailCoordinator, times(1)).getDetail(reportId);

            // 3. Flash errorMessage attribute
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo("Không tìm thấy báo cáo: " + reportId);

            // 4. No report in model
            assertThat(model.get("report")).isNull();

            // 5. No-cache headers still set
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
            assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(response.getDateHeader("Expires")).isEqualTo(0L);
        }

        @Test
        @DisplayName("Case C: Unrelated runtime error propagates without being swallowed")
        void shouldPropagateUnrelatedRuntimeException() {
            ExtendedModelMap model = new ExtendedModelMap();
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(detailCoordinator.getDetail(reportId)).thenThrow(new IllegalStateException("Database connection failed"));

            assertThatThrownBy(() -> controller.reportDetail(reportId, model, response, redirectAttributes))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Database connection failed");

            verify(detailCoordinator, times(1)).getDetail(reportId);
        }
    }

    @Nested
    @DisplayName("GET /admin/comments/reports/{reportId}/context - Public Context Navigation")
    class ContextNavigationTests {

        @Test
        @DisplayName("Case A: Novel context redirect - redirects to public chapter reader with commentId, threadId, and anchor")
        void shouldRedirectToNovelChapterContextSuccessfully() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            AdminCommentReportContextNavigationDTO navDto = AdminCommentReportContextNavigationDTO.available(
                    reportId,
                    commentId,
                    threadId,
                    CommentTargetType.NOVEL_CHAPTER,
                    "quyen-1-chuong-1",
                    null
            );
            when(navigationCoordinator.resolveNavigation(reportId)).thenReturn(navDto);

            String redirect = controller.navigateToContext(reportId, response, redirectAttributes);

            assertThat(redirect).isEqualTo(
                    "redirect:/novel/chapters/quyen-1-chuong-1?commentId=" + commentId + "&threadId=" + threadId + "#novelChapterComments"
            );
            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);

            // HTTP no-cache headers verification
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
            assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(response.getDateHeader("Expires")).isEqualTo(0L);

            assertThat(redirectAttributes.getFlashAttributes()).isEmpty();
        }

        @Test
        @DisplayName("Case B: Wiki context redirect - maps raw structural CHARACTER to character and redirects")
        void shouldRedirectToWikiArticleContextSuccessfully() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            AdminCommentReportContextNavigationDTO navDto = AdminCommentReportContextNavigationDTO.available(
                    reportId,
                    commentId,
                    threadId,
                    CommentTargetType.WIKI_ARTICLE,
                    "tran-binh-an",
                    "CHARACTER" // raw structural type
            );
            when(navigationCoordinator.resolveNavigation(reportId)).thenReturn(navDto);
            when(articleTypePathMapper.toPath(ArticleType.CHARACTER)).thenReturn("character");

            String redirect = controller.navigateToContext(reportId, response, redirectAttributes);

            assertThat(redirect).isEqualTo(
                    "redirect:/wiki/character/tran-binh-an?commentId=" + commentId + "&threadId=" + threadId + "#wikiDiscussion"
            );
            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);
            verify(articleTypePathMapper, times(1)).toPath(ArticleType.CHARACTER);

            // HTTP no-cache headers verification
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
            assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(response.getDateHeader("Expires")).isEqualTo(0L);

            assertThat(redirectAttributes.getFlashAttributes()).isEmpty();
        }

        @Test
        @DisplayName("Case C: Unavailable redirect - redirects back to detail page with flash errorMessage")
        void shouldRedirectBackToDetailWhenUnavailable() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            AdminCommentReportContextNavigationDTO navDto = AdminCommentReportContextNavigationDTO.unavailable(reportId);
            when(navigationCoordinator.resolveNavigation(reportId)).thenReturn(navDto);

            String redirect = controller.navigateToContext(reportId, response, redirectAttributes);

            assertThat(redirect).isEqualTo("redirect:/admin/comments/reports/" + reportId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo("Không thể mở bình luận trong ngữ cảnh hiện tại.");

            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);

            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        }

        @Test
        @DisplayName("Case D: Report not found redirect - catches InteractionReportNotFoundException and redirects to queue")
        void shouldRedirectToQueueWhenReportNotFoundInNavigation() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(navigationCoordinator.resolveNavigation(reportId)).thenThrow(new InteractionReportNotFoundException(reportId));

            String redirect = controller.navigateToContext(reportId, response, redirectAttributes);

            assertThat(redirect).isEqualTo("redirect:/admin/comments/reports");
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo("Không tìm thấy báo cáo: " + reportId);

            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        }

        @Test
        @DisplayName("Case E: Exception propagation - unexpected exception is rethrown")
        void shouldPropagateUnexpectedExceptionInNavigation() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            when(navigationCoordinator.resolveNavigation(reportId)).thenThrow(new IllegalStateException("Navigation failed"));

            assertThatThrownBy(() -> controller.navigateToContext(reportId, response, redirectAttributes))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Navigation failed");

            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);
        }

        @Test
        @DisplayName("Case F: Invalid articleType - gracefully degrades to detail redirect with context-unavailable flash")
        void shouldDegradeGracefullyWhenArticleTypeIsInvalid() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            AdminCommentReportContextNavigationDTO navDto = AdminCommentReportContextNavigationDTO.available(
                    reportId,
                    commentId,
                    threadId,
                    CommentTargetType.WIKI_ARTICLE,
                    "invalid-article",
                    "NOT_A_VALID_ARTICLE_TYPE" // will cause ArticleType.valueOf to throw IllegalArgumentException
            );
            when(navigationCoordinator.resolveNavigation(reportId)).thenReturn(navDto);

            String redirect = controller.navigateToContext(reportId, response, redirectAttributes);

            assertThat(redirect).isEqualTo("redirect:/admin/comments/reports/" + reportId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo("Không thể mở bình luận trong ngữ cảnh hiện tại.");

            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);
            verify(articleTypePathMapper, never()).toPath(any());
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        }

        @Test
        @DisplayName("Case G: Unrelated mapper runtime exception propagates without being swallowed")
        void shouldPropagateUnrelatedMapperRuntimeException() {
            MockHttpServletResponse response = new MockHttpServletResponse();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            AdminCommentReportContextNavigationDTO navDto = AdminCommentReportContextNavigationDTO.available(
                    reportId,
                    commentId,
                    threadId,
                    CommentTargetType.WIKI_ARTICLE,
                    "tran-binh-an",
                    "CHARACTER"
            );
            when(navigationCoordinator.resolveNavigation(reportId)).thenReturn(navDto);
            when(articleTypePathMapper.toPath(ArticleType.CHARACTER))
                    .thenThrow(new IllegalStateException("Mapper crash defect"));

            assertThatThrownBy(() -> controller.navigateToContext(reportId, response, redirectAttributes))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Mapper crash defect");

            verify(navigationCoordinator, times(1)).resolveNavigation(reportId);
            verify(articleTypePathMapper, times(1)).toPath(ArticleType.CHARACTER);
        }
    }

    private AdminCommentReportDetailDTO createSampleDetailDTO() {
        return new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                "Mô tả báo cáo",
                "Snapshot evidence",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png"),
                null,
                null,
                null,
                true,
                "Live comment body",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                baseTime.minusSeconds(100),
                null,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
                AdminCommentReportTargetDTO.unresolved(CommentTargetType.NOVEL_CHAPTER, targetId)
        );
    }
}
