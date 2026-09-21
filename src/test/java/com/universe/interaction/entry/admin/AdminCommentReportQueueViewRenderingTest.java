package com.universe.interaction.entry.admin;

import com.universe.identity.entry.web.advice.CurrentUserAdvice;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueueItemDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportTargetDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportUserDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Dedicated rendering regression tests for the Admin comment report queue Thymeleaf view.
 *
 * <p>Exercises the actual {@code /admin/comments/reports} route with the real Thymeleaf template engine
 * and view resolver, compiling {@code admin/comments/reports.html} and its nested layout fragments
 * (sidebar, header) without mocking {@code ThymeleafViewResolver}.
 */
@WebMvcTest(
        controllers = AdminCommentReportQueueController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        CurrentUserAdvice.class,
                        AccountStatusFilter.class
                }
        )
)
@AutoConfigureMockMvc(addFilters = false)
class AdminCommentReportQueueViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminCommentReportQueueCoordinator coordinator;

    @Test
    @DisplayName("Case 1: Empty queue renders successfully with real Thymeleaf, empty state panel, and sidebar")
    void shouldRenderEmptyQueueWithRealThymeleaf() throws Exception {
        when(coordinator.getReportQueue(any()))
                .thenReturn(AdminCommentReportQueuePageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                .andExpect(content().string(containsString("Báo cáo bình luận")))
                .andExpect(content().string(containsString("Không có báo cáo phù hợp")))
                // Sidebar navigation: comment reports item exists and is active
                .andExpect(content().string(containsString("/admin/comments/reports")))
                .andExpect(content().string(containsString("sidebar-link")))
                .andExpect(content().string(containsString("active")));

        verify(coordinator).getReportQueue(any());
    }

    @Test
    @DisplayName("Case 2: Populated queue with resolved Novel and Wiki targets, avatars, XSS snapshot escaping, and pagination controls")
    void shouldRenderResolvedNovelAndWikiReportsWithRealThymeleaf() throws Exception {
        UUID reportNovelId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID reportWikiId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID commentNovelId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID commentWikiId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID reporterAId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID authorAId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID reporterBId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID authorBId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID chapterId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID articleId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

        // Item A: Active comment on Novel chapter, with HTML in snapshot for XSS verification
        AdminCommentReportQueueItemDTO novelItem = new AdminCommentReportQueueItemDTO(
                reportNovelId,
                commentNovelId,
                AdminCommentReportUserDTO.resolved(reporterAId, "Alice Reporter", "https://img.local/alice.png"),
                ReportReason.SPAM,
                "Báo cáo spam chương truyện",
                "<script>alert(\"x\")</script><b>evidence</b>",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(authorAId, "Bob Author", null),
                CommentStatus.ACTIVE,
                new AdminCommentReportTargetDTO(
                        CommentTargetType.NOVEL_CHAPTER,
                        chapterId,
                        true,
                        "Chương 42: Kiếm Khí Trường Hà",
                        "tap-1/chuong-42",
                        "PUBLISHED",
                        42,
                        null
                )
        );

        // Item B: Soft-deleted comment on Wiki article, terminal status
        AdminCommentReportQueueItemDTO wikiItem = new AdminCommentReportQueueItemDTO(
                reportWikiId,
                commentWikiId,
                AdminCommentReportUserDTO.resolved(reporterBId, "Charlie Reporter", null),
                ReportReason.HARASSMENT,
                null,
                "Bình luận quấy rối đã bị xóa",
                ReportStatus.RESOLVED_ACTION_TAKEN,
                baseTime.plusSeconds(300),
                AdminCommentReportUserDTO.resolved(authorBId, "Dave Author", "https://img.local/dave.png"),
                CommentStatus.DELETED,
                new AdminCommentReportTargetDTO(
                        CommentTargetType.WIKI_ARTICLE,
                        articleId,
                        true,
                        "Trần Bình An",
                        "tran-binh-an",
                        "PUBLISHED",
                        null,
                        "CHARACTER"
                )
        );

        // Page 1 of size 2 with totalElements = 5 -> totalPages = 3, hasPrevious = true, hasNext = true
        AdminCommentReportQueuePageDTO pageDTO = new AdminCommentReportQueuePageDTO(
                List.of(novelItem, wikiItem),
                1,
                2,
                5L
        );
        when(coordinator.getReportQueue(any())).thenReturn(pageDTO);

        mockMvc.perform(get("/admin/comments/reports")
                        .param("status", "ALL")
                        .param("sort", "NEWEST")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                // Novel target and chapter link
                .andExpect(content().string(containsString("Chương 42: Kiếm Khí Trường Hà")))
                .andExpect(content().string(containsString("Xem Chapter")))
                .andExpect(content().string(containsString("/admin/novel/chapters/" + chapterId)))
                // Wiki target and article link
                .andExpect(content().string(containsString("Trần Bình An")))
                .andExpect(content().string(containsString("Xem bài Wiki")))
                .andExpect(content().string(containsString("/admin/wiki/articles/" + articleId)))
                // Lifecycle comment status badges
                .andExpect(content().string(containsString("Đang hiển thị")))
                .andExpect(content().string(containsString("Đã xóa")))
                // Avatar image with referrerpolicy
                .andExpect(content().string(containsString("referrerpolicy=\"no-referrer\"")))
                .andExpect(content().string(containsString("https://img.local/alice.png")))
                .andExpect(content().string(containsString("https://img.local/dave.png")))
                // XSS safety: snapshot is rendered HTML-escaped, never raw executable
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("&lt;b&gt;evidence&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(\"x\")</script>"))))
                // Detail view links
                .andExpect(content().string(containsString("Xem chi tiết")))
                .andExpect(content().string(containsString("/admin/comments/reports/" + reportNovelId)))
                .andExpect(content().string(containsString("/admin/comments/reports/" + reportWikiId)))
                // Pagination controls and indicators (page 2 of 3)
                .andExpect(content().string(containsString("Trang trước")))
                .andExpect(content().string(containsString("Trang sau")))
                .andExpect(content().string(containsString("Trang <strong>2</strong> / <strong>3</strong>")));

        verify(coordinator).getReportQueue(any());
    }

    @Test
    @DisplayName("Case 3: Unresolved user identities and target metadata render fallback UI gracefully without throwing")
    void shouldRenderUnresolvedUsersAndTargetGracefully() throws Exception {
        UUID reportId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID commentId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID unresolvedReporterId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID unresolvedAuthorId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID unresolvedTargetId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        Instant baseTime = Instant.parse("2026-09-21T11:00:00Z");

        // Item with unresolved reporter, author, target, and whitespace-only description
        AdminCommentReportQueueItemDTO unresolvedItem = new AdminCommentReportQueueItemDTO(
                reportId,
                commentId,
                AdminCommentReportUserDTO.unresolved(unresolvedReporterId),
                ReportReason.OTHER,
                "   ", // Whitespace-only description to verify blank-guard
                "Bằng chứng vi phạm được lưu giữ an toàn",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.unresolved(unresolvedAuthorId),
                CommentStatus.ACTIVE,
                AdminCommentReportTargetDTO.unresolved(CommentTargetType.NOVEL_CHAPTER, unresolvedTargetId)
        );

        AdminCommentReportQueuePageDTO pageDTO = new AdminCommentReportQueuePageDTO(
                List.of(unresolvedItem),
                0,
                20,
                1L
        );
        when(coordinator.getReportQueue(any())).thenReturn(pageDTO);

        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                // Fallback markers for unresolved foreign entities
                .andExpect(content().string(containsString("Người dùng không khả dụng")))
                .andExpect(content().string(containsString("Nội dung không khả dụng")))
                // Preserved scalar UUID identifiers
                .andExpect(content().string(containsString(unresolvedReporterId.toString())))
                .andExpect(content().string(containsString(unresolvedAuthorId.toString())))
                .andExpect(content().string(containsString(unresolvedTargetId.toString())))
                // Blank description guard: description panel must NOT be rendered for whitespace-only text
                .andExpect(content().string(not(containsString("comment-report-description"))));

        verify(coordinator).getReportQueue(any());
    }

    @Test
    @DisplayName("Case 4: Flash errorMessage renders visible alert with th:text escaping")
    void shouldRenderFlashErrorMessageWithRealThymeleaf() throws Exception {
        when(coordinator.getReportQueue(any()))
                .thenReturn(AdminCommentReportQueuePageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/comments/reports")
                        .flashAttr("errorMessage", "Không tìm thấy báo cáo: <script>alert(\"xss\")</script>"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                .andExpect(content().string(containsString("Không tìm thấy báo cáo:")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(\"xss\")</script>"))))
                .andExpect(content().string(not(containsString("<script>"))));

        verify(coordinator).getReportQueue(any());
    }
}
