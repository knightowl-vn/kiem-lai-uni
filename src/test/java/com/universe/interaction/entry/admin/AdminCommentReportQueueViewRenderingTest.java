package com.universe.interaction.entry.admin;

import com.universe.identity.entry.web.advice.CurrentUserAdvice;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
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
    @DisplayName("Empty queue renders successfully with real Thymeleaf, empty state panel, and sidebar")
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
    @DisplayName("Test A: PENDING view renders active tab, cards, target links, XSS escaping, and no moderation action or resolver")
    void shouldRenderPendingQueueWithActiveTabAndNoActionOrResolver() throws Exception {
        UUID reportNovelId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID commentNovelId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID reporterId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID authorId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID chapterId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

        AdminCommentReportQueueItemDTO pendingItem = new AdminCommentReportQueueItemDTO(
                reportNovelId,
                commentNovelId,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", "https://img.local/alice.png"),
                ReportReason.SPAM,
                "Báo cáo spam chương truyện",
                "<script>alert(\"x\")</script><b>evidence</b>",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
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
                ),
                null,
                null,
                null
        );

        AdminCommentReportQueuePageDTO pageDTO = new AdminCommentReportQueuePageDTO(
                List.of(pendingItem),
                0,
                20,
                1L
        );
        when(coordinator.getReportQueue(any())).thenReturn(pageDTO);

        mockMvc.perform(get("/admin/comments/reports"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                // Tab "Cần xử lý" is active, "Đã xử lý" link exists
                .andExpect(content().string(containsString("comment-reports-tab active")))
                .andExpect(content().string(containsString("Cần xử lý")))
                .andExpect(content().string(containsString("Đã xử lý")))
                .andExpect(content().string(containsString("scope=PROCESSED")))
                // Card rendered
                .andExpect(content().string(containsString("Chương 42: Kiếm Khí Trường Hà")))
                .andExpect(content().string(containsString("/admin/novel/chapters/" + chapterId)))
                .andExpect(content().string(containsString("Chờ xử lý")))
                // NO action label rendered
                .andExpect(content().string(not(containsString("Xóa bình luận"))))
                .andExpect(content().string(not(containsString("Không thực hiện hành động"))))
                // NO resolver panel rendered
                .andExpect(content().string(not(containsString("Người xử lý"))))
                // Sort control IS present in PENDING view
                .andExpect(content().string(containsString("Sắp xếp")))
                // XSS safety: snapshot is rendered HTML-escaped, never raw executable
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("&lt;b&gt;evidence&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(\"x\")</script>"))));

        verify(coordinator).getReportQueue(any());
    }

    @Test
    @DisplayName("Test B: PROCESSED view with DELETE_COMMENT renders active tab, action label, resolver, timestamp, and hides sort control")
    void shouldRenderProcessedQueueWithDeleteCommentAction() throws Exception {
        UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID reporterId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID authorId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID resolverId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID chapterId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        Instant createdAt = Instant.parse("2026-09-21T10:00:00Z");
        Instant resolvedAt = Instant.parse("2026-09-21T12:00:00Z");

        AdminCommentReportQueueItemDTO processedItem = new AdminCommentReportQueueItemDTO(
                reportId,
                commentId,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", null),
                ReportReason.HARASSMENT,
                "Báo cáo quấy rối",
                "Nội dung vi phạm quấy rối nghiêm trọng",
                ReportStatus.RESOLVED_ACTION_TAKEN,
                createdAt,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
                CommentStatus.DELETED,
                new AdminCommentReportTargetDTO(
                        CommentTargetType.NOVEL_CHAPTER,
                        chapterId,
                        true,
                        "Chương 10: Vấn Kiếm",
                        "tap-1/chuong-10",
                        "PUBLISHED",
                        10,
                        null
                ),
                ReportModerationAction.DELETE_COMMENT,
                AdminCommentReportUserDTO.resolved(resolverId, "Moderator User", "https://img.local/mod.png"),
                resolvedAt
        );

        AdminCommentReportQueuePageDTO pageDTO = new AdminCommentReportQueuePageDTO(
                List.of(processedItem),
                0,
                20,
                1L
        );
        when(coordinator.getReportQueue(any())).thenReturn(pageDTO);

        mockMvc.perform(get("/admin/comments/reports").param("scope", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                // Tab "Đã xử lý" has active class
                .andExpect(content().string(containsString("comment-reports-tab active")))
                .andExpect(content().string(containsString("Đã xử lý")))
                // Status and exact action label
                .andExpect(content().string(containsString("Đã xử lý — có hành động")))
                .andExpect(content().string(containsString("Xóa bình luận")))
                // Resolver display name and avatar
                .andExpect(content().string(containsString("Người xử lý")))
                .andExpect(content().string(containsString("Moderator User")))
                .andExpect(content().string(containsString("https://img.local/mod.png")))
                // Formatted resolved timestamp label
                .andExpect(content().string(containsString("Đã xử lý: ")))
                // Body snapshot rendered
                .andExpect(content().string(containsString("Nội dung vi phạm quấy rối nghiêm trọng")))
                // Sort control is absent / hidden in PROCESSED view
                .andExpect(content().string(not(containsString("<label for=\"sort\">Sắp xếp</label>"))));

        verify(coordinator).getReportQueue(any());
    }

    @Test
    @DisplayName("Test C: PROCESSED view with NO_ACTION renders 'Không thực hiện hành động' label and resolver info")
    void shouldRenderProcessedQueueWithNoAction() throws Exception {
        UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID reporterId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID authorId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID resolverId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID articleId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        Instant createdAt = Instant.parse("2026-09-21T10:00:00Z");
        Instant resolvedAt = Instant.parse("2026-09-21T12:00:00Z");

        AdminCommentReportQueueItemDTO noActionItem = new AdminCommentReportQueueItemDTO(
                reportId,
                commentId,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", null),
                ReportReason.OTHER,
                "Không vi phạm",
                "Bình luận thảo luận bình thường",
                ReportStatus.RESOLVED_NO_ACTION,
                createdAt,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
                CommentStatus.ACTIVE,
                new AdminCommentReportTargetDTO(
                        CommentTargetType.WIKI_ARTICLE,
                        articleId,
                        true,
                        "Trần Bình An",
                        "tran-binh-an",
                        "PUBLISHED",
                        null,
                        "CHARACTER"
                ),
                ReportModerationAction.NO_ACTION,
                AdminCommentReportUserDTO.resolved(resolverId, "Moderator User", null),
                resolvedAt
        );

        AdminCommentReportQueuePageDTO pageDTO = new AdminCommentReportQueuePageDTO(
                List.of(noActionItem),
                0,
                20,
                1L
        );
        when(coordinator.getReportQueue(any())).thenReturn(pageDTO);

        mockMvc.perform(get("/admin/comments/reports").param("scope", "PROCESSED"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                .andExpect(content().string(containsString("Đã xử lý — không hành động")))
                .andExpect(content().string(containsString("Không thực hiện hành động")))
                .andExpect(content().string(containsString("Moderator User")));

        verify(coordinator).getReportQueue(any());
    }

    @Test
    @DisplayName("Test D: Scope is preserved in pagination links and filter form when in PROCESSED view")
    void shouldPreserveScopeInRenderedNavigationForProcessedQueue() throws Exception {
        UUID reportId1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID reportId2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID commentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID reporterId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID authorId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID resolverId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        Instant now = Instant.parse("2026-09-21T12:00:00Z");

        AdminCommentReportTargetDTO target = new AdminCommentReportTargetDTO(
                CommentTargetType.NOVEL_CHAPTER,
                UUID.randomUUID(),
                true,
                "Chương 1",
                "tap-1/chuong-1",
                "PUBLISHED",
                1,
                null
        );

        AdminCommentReportQueueItemDTO item1 = new AdminCommentReportQueueItemDTO(
                reportId1, commentId, AdminCommentReportUserDTO.resolved(reporterId, "User 1", null),
                ReportReason.SPAM, null, "Snapshot 1", ReportStatus.RESOLVED_ACTION_TAKEN,
                now.minusSeconds(3600), AdminCommentReportUserDTO.resolved(authorId, "Author", null),
                CommentStatus.DELETED, target, ReportModerationAction.DELETE_COMMENT,
                AdminCommentReportUserDTO.resolved(resolverId, "Mod", null), now
        );
        AdminCommentReportQueueItemDTO item2 = new AdminCommentReportQueueItemDTO(
                reportId2, commentId, AdminCommentReportUserDTO.resolved(reporterId, "User 2", null),
                ReportReason.OTHER, null, "Snapshot 2", ReportStatus.RESOLVED_NO_ACTION,
                now.minusSeconds(1800), AdminCommentReportUserDTO.resolved(authorId, "Author", null),
                CommentStatus.ACTIVE, target, ReportModerationAction.NO_ACTION,
                AdminCommentReportUserDTO.resolved(resolverId, "Mod", null), now
        );

        // Page 1 of size 2 with totalElements = 5 -> totalPages = 3, hasPrevious = true, hasNext = true
        AdminCommentReportQueuePageDTO pageDTO = new AdminCommentReportQueuePageDTO(
                List.of(item1, item2),
                1,
                2,
                5L
        );
        when(coordinator.getReportQueue(any())).thenReturn(pageDTO);

        mockMvc.perform(get("/admin/comments/reports")
                        .param("scope", "PROCESSED")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/reports"))
                // Hidden input in filter form
                .andExpect(content().string(containsString("<input type=\"hidden\" name=\"scope\" value=\"PROCESSED\">")))
                // Filter reset link preserves scope
                .andExpect(content().string(containsString("/admin/comments/reports?scope=PROCESSED")))
                // Pagination links preserve scope=PROCESSED
                .andExpect(content().string(containsString("scope=PROCESSED")))
                .andExpect(content().string(containsString("Trang trước")))
                .andExpect(content().string(containsString("Trang sau")))
                .andExpect(content().string(containsString("page=0")))
                .andExpect(content().string(containsString("page=2")));

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
                AdminCommentReportTargetDTO.unresolved(CommentTargetType.NOVEL_CHAPTER, unresolvedTargetId),
                null,
                null,
                null
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
