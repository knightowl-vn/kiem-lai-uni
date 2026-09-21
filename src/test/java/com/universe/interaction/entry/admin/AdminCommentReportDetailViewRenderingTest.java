package com.universe.interaction.entry.admin;

import com.universe.identity.entry.web.advice.CurrentUserAdvice;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportTargetDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportUserDTO;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;
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
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Real Thymeleaf acceptance tests for the Admin comment report detail view.
 *
 * <p>Validates {@code admin/comments/report-detail.html} rendering against real Thymeleaf templates,
 * fragments (sidebar, header), and structural styling without mocking {@code ThymeleafViewResolver}.
 */
@WebMvcTest(
        controllers = AdminCommentReportDetailController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        CurrentUserAdvice.class,
                        AccountStatusFilter.class
                }
        )
)
@AutoConfigureMockMvc(addFilters = false)
class AdminCommentReportDetailViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminCommentReportDetailCoordinator coordinator;

    @MockBean
    private AdminCommentReportContextNavigationCoordinator navigationCoordinator;

    @MockBean
    private ArticleTypePathMapper articleTypePathMapper;

    private final UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID reporterId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID authorId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID resolverId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID targetId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    @DisplayName("Case A: Active Novel Comment - PENDING, live body, resolved PUBLISHED Novel chapter, renders both Xem Chapter and context action")
    void shouldRenderActiveNovelCommentReportWithRealThymeleaf() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                "Báo cáo bình luận quảng cáo link xấu",
                "Snapshot lịch sử vi phạm ban đầu lúc 10h",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", "https://img.local/alice.png"),
                null,
                null,
                null,
                true,
                "Nội dung bình luận trực tiếp hiện tại đang hiển thị",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(3600),
                baseTime.minusSeconds(1800),
                null,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", "https://img.local/bob.png"),
                new AdminCommentReportTargetDTO(
                        CommentTargetType.NOVEL_CHAPTER,
                        targetId,
                        true,
                        "Kiếm Khí Trường Hà",
                        "tap-1/chuong-42",
                        "PUBLISHED",
                        42,
                        null
                )
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Page title & heading
                .andExpect(content().string(containsString("Chi tiết báo cáo bình luận")))
                .andExpect(content().string(containsString("INTERACTION / MODERATION")))
                .andExpect(content().string(containsString("← Quay lại danh sách báo cáo")))
                // Historical evidence
                .andExpect(content().string(containsString("Nội dung tại thời điểm báo cáo")))
                .andExpect(content().string(containsString("Snapshot lịch sử vi phạm ban đầu lúc 10h")))
                .andExpect(content().string(containsString("Ghi chú của người báo cáo")))
                .andExpect(content().string(containsString("Báo cáo bình luận quảng cáo link xấu")))
                // Current comment state
                .andExpect(content().string(containsString("Nội dung bình luận hiện tại")))
                .andExpect(content().string(containsString("Nội dung bình luận trực tiếp hiện tại đang hiển thị")))
                .andExpect(content().string(containsString("Đang hiển thị")))
                // Users
                .andExpect(content().string(containsString("Alice Reporter")))
                .andExpect(content().string(containsString("Bob Author")))
                .andExpect(content().string(containsString("referrerpolicy=\"no-referrer\"")))
                // Novel target - existing editor link preserved
                .andExpect(content().string(containsString("Chương 42: Kiếm Khí Trường Hà")))
                .andExpect(content().string(containsString("Xem Chapter")))
                .andExpect(content().string(containsString("/admin/novel/chapters/" + targetId)))
                // Novel target - new context action rendered with new-tab safety
                .andExpect(content().string(containsString("Mở bình luận trong ngữ cảnh ↗")))
                .andExpect(content().string(containsString("/admin/comments/reports/" + reportId + "/context")))
                .andExpect(content().string(containsString("target=\"_blank\"")))
                .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")))
                // Pending status - no resolver rendered
                .andExpect(content().string(containsString("Chờ xử lý")))
                .andExpect(content().string(not(containsString("resolver-card"))))
                .andExpect(content().string(not(containsString("Thời gian xử lý:"))));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case B: Active Wiki Comment - resolved PUBLISHED Wiki article renders both Xem bài Wiki and context action")
    void shouldRenderActiveWikiCommentReportWithContextAction() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                null,
                "Snapshot spam wiki",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", null),
                null,
                null,
                null,
                true,
                "Bình luận wiki đang hiển thị",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                null,
                null,
                AdminCommentReportUserDTO.resolved(authorId, "Dave Author", null),
                new AdminCommentReportTargetDTO(
                        CommentTargetType.WIKI_ARTICLE,
                        targetId,
                        true,
                        "Trần Bình An",
                        "tran-binh-an",
                        "PUBLISHED",
                        null,
                        "CHARACTER"
                )
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Existing Wiki editor link preserved
                .andExpect(content().string(containsString("Trần Bình An")))
                .andExpect(content().string(containsString("Xem bài Wiki")))
                .andExpect(content().string(containsString("/admin/wiki/articles/" + targetId)))
                // New context action rendered with new-tab safety
                .andExpect(content().string(containsString("Mở bình luận trong ngữ cảnh ↗")))
                .andExpect(content().string(containsString("/admin/comments/reports/" + reportId + "/context")))
                .andExpect(content().string(containsString("target=\"_blank\"")))
                .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case C: DELETED Current Comment + PUBLISHED Target - context action remains rendered alongside tombstone")
    void shouldRenderResolvedDeletedWikiCommentReportWithRealThymeleaf() throws Exception {
        String historicalSnapshot = "Bằng chứng xúc phạm lịch sử [SNAPSHOT-SENTINEL-999]";

        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.HARASSMENT,
                null,
                historicalSnapshot,
                ReportStatus.RESOLVED_ACTION_TAKEN,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Charlie Reporter", null),
                resolverId,
                AdminCommentReportUserDTO.resolved(resolverId, "Admin Eva", "https://img.local/eva.png"),
                baseTime.plusSeconds(300),
                true,
                null,
                CommentStatus.DELETED,
                baseTime.minusSeconds(1200),
                baseTime.minusSeconds(600),
                baseTime.plusSeconds(250),
                AdminCommentReportUserDTO.resolved(authorId, "Dave Author", null),
                new AdminCommentReportTargetDTO(
                        CommentTargetType.WIKI_ARTICLE,
                        targetId,
                        true,
                        "Trần Bình An",
                        "tran-binh-an",
                        "PUBLISHED",
                        null,
                        "CHARACTER"
                )
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Historical snapshot remains visible in evidence section
                .andExpect(content().string(containsString("Nội dung tại thời điểm báo cáo")))
                .andExpect(content().string(containsString(historicalSnapshot)))
                // Current deleted state
                .andExpect(content().string(containsString("Nội dung bình luận hiện tại")))
                .andExpect(content().string(containsString("Bình luận đã bị xóa.")))
                .andExpect(content().string(containsString("Đã xóa")))
                .andExpect(content().string(containsString("Thời gian xóa:")))
                // CRITICAL: Historical snapshot is NOT rendered as the current comment body
                .andExpect(content().string(not(containsString("comment-report-detail-current-text"))))
                // Resolver info
                .andExpect(content().string(containsString("Người xử lý")))
                .andExpect(content().string(containsString("Admin Eva")))
                .andExpect(content().string(containsString("Thời gian xử lý:")))
                .andExpect(content().string(containsString("Đã xử lý — có hành động")))
                // Wiki target - existing editor link
                .andExpect(content().string(containsString("Trần Bình An")))
                .andExpect(content().string(containsString("Xem bài Wiki")))
                .andExpect(content().string(containsString("/admin/wiki/articles/" + targetId)))
                // DELETED comment + PUBLISHED target: context action REMAINS rendered
                .andExpect(content().string(containsString("Mở bình luận trong ngữ cảnh ↗")))
                .andExpect(content().string(containsString("/admin/comments/reports/" + reportId + "/context")))
                .andExpect(content().string(containsString("target=\"_blank\"")))
                .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case D: Missing Current Comment - currentCommentAvailable=false omits context action")
    void shouldRenderMissingCurrentCommentReportGracefully() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.OTHER,
                null,
                "Snapshot của bình luận đã bị gỡ bỏ hoàn toàn khỏi hệ thống",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Frank Reporter", null),
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Historical evidence still rendered
                .andExpect(content().string(containsString("Snapshot của bình luận đã bị gỡ bỏ hoàn toàn khỏi hệ thống")))
                // Missing current comment fallbacks
                .andExpect(content().string(containsString("Bình luận hiện tại không còn khả dụng.")))
                .andExpect(content().string(containsString("Bình luận không khả dụng")))
                .andExpect(content().string(containsString("Nội dung mục tiêu hiện không khả dụng.")))
                // No target navigation links or context action
                .andExpect(content().string(not(containsString("Xem Chapter"))))
                .andExpect(content().string(not(containsString("Xem bài Wiki"))))
                .andExpect(content().string(not(containsString("Mở bình luận trong ngữ cảnh"))))
                .andExpect(content().string(not(containsString("/context"))));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case E: Unresolved Target - target.resolved=false omits context action")
    void shouldRenderUnresolvedEntitiesWithPreservedUuids() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPOILER,
                null,
                "Nội dung tiết lộ cốt truyện kết thúc truyện",
                ReportStatus.RESOLVED_NO_ACTION,
                baseTime,
                AdminCommentReportUserDTO.unresolved(reporterId),
                resolverId,
                AdminCommentReportUserDTO.unresolved(resolverId),
                baseTime.plusSeconds(500),
                true,
                "Nội dung tiết lộ cốt truyện kết thúc truyện",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                null,
                null,
                AdminCommentReportUserDTO.unresolved(authorId),
                AdminCommentReportTargetDTO.unresolved(CommentTargetType.NOVEL_CHAPTER, targetId)
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Unresolved users show fallback text
                .andExpect(content().string(containsString("Người dùng không khả dụng")))
                // Preserved UUIDs
                .andExpect(content().string(containsString(reporterId.toString())))
                .andExpect(content().string(containsString(authorId.toString())))
                .andExpect(content().string(containsString(resolverId.toString())))
                // Unresolved target shows fallback and preserved UUID
                .andExpect(content().string(containsString("Nội dung không khả dụng")))
                .andExpect(content().string(containsString(targetId.toString())))
                // No navigation links to unresolved entities
                .andExpect(content().string(not(containsString("Xem Chapter"))))
                .andExpect(content().string(not(containsString("Xem bài Wiki"))))
                .andExpect(content().string(not(containsString("Mở bình luận trong ngữ cảnh"))))
                .andExpect(content().string(not(containsString("/context"))));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case F: DRAFT Target - omits context action while preserving editor link")
    void shouldOmitContextActionWhenTargetIsDraft() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                null,
                "Snapshot",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", null),
                null,
                null,
                null,
                true,
                "Bình luận",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                null,
                null,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
                new AdminCommentReportTargetDTO(
                        CommentTargetType.NOVEL_CHAPTER,
                        targetId,
                        true,
                        "Chương Nháp",
                        "chuong-nhap",
                        "DRAFT", // target is DRAFT
                        1,
                        null
                )
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Existing editor link remains intact
                .andExpect(content().string(containsString("Xem Chapter")))
                .andExpect(content().string(containsString("/admin/novel/chapters/" + targetId)))
                // Context action is omitted for DRAFT target
                .andExpect(content().string(not(containsString("Mở bình luận trong ngữ cảnh"))))
                .andExpect(content().string(not(containsString("/context"))));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case G: ARCHIVED Target - omits context action while preserving editor link")
    void shouldOmitContextActionWhenTargetIsArchived() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                null,
                "Snapshot",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice Reporter", null),
                null,
                null,
                null,
                true,
                "Bình luận",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                null,
                null,
                AdminCommentReportUserDTO.resolved(authorId, "Bob Author", null),
                new AdminCommentReportTargetDTO(
                        CommentTargetType.WIKI_ARTICLE,
                        targetId,
                        true,
                        "Bài Viết Lưu Trữ",
                        "bai-viet-luu-tru",
                        "ARCHIVED", // target is ARCHIVED
                        null,
                        "CHARACTER"
                )
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Existing editor link remains intact
                .andExpect(content().string(containsString("Xem bài Wiki")))
                .andExpect(content().string(containsString("/admin/wiki/articles/" + targetId)))
                // Context action is omitted for ARCHIVED target
                .andExpect(content().string(not(containsString("Mở bình luận trong ngữ cảnh"))))
                .andExpect(content().string(not(containsString("/context"))));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case H: Flash Error - renders errorMessage alert when present in flash attributes")
    void shouldRenderFlashErrorMessageWhenPresent() throws Exception {
        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.SPAM,
                null,
                "Snapshot",
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, "Alice", null),
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId)
                        .flashAttr("errorMessage", "Không thể mở bình luận trong ngữ cảnh hiện tại."))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                .andExpect(content().string(containsString("Không thể mở bình luận trong ngữ cảnh hiện tại.")))
                .andExpect(content().string(containsString("admin-form-error alert-error")));

        verify(coordinator).getDetail(reportId);
    }

    @Test
    @DisplayName("Case I: XSS Escaping - dynamic content is escaped and never executed as raw HTML")
    void shouldSafelyEscapeDynamicContentAgainstXss() throws Exception {
        String scriptSentinel = "<script>alert(\"x\")</script>";
        String htmlSentinel = "<b>bold-evidence</b>";

        AdminCommentReportDetailDTO detailDTO = new AdminCommentReportDetailDTO(
                reportId,
                commentId,
                ReportReason.OTHER,
                scriptSentinel + "description",
                scriptSentinel + htmlSentinel,
                ReportStatus.PENDING,
                baseTime,
                AdminCommentReportUserDTO.resolved(reporterId, scriptSentinel + "reporter", null),
                null,
                null,
                null,
                true,
                scriptSentinel + "live-body",
                CommentStatus.ACTIVE,
                baseTime.minusSeconds(100),
                null,
                null,
                AdminCommentReportUserDTO.resolved(authorId, scriptSentinel + "author", null),
                new AdminCommentReportTargetDTO(
                        CommentTargetType.NOVEL_CHAPTER,
                        targetId,
                        true,
                        scriptSentinel + "chapter-title",
                        scriptSentinel + "chapter-slug",
                        "PUBLISHED",
                        99,
                        null
                )
        );

        when(coordinator.getDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/comments/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/comments/report-detail"))
                // Escaped entities must be present
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("&lt;b&gt;bold-evidence&lt;/b&gt;")))
                // Raw unescaped executable script tags must NOT be present
                .andExpect(content().string(not(containsString("<script>alert(\"x\")</script>"))))
                .andExpect(content().string(not(containsString("<script>"))));

        verify(coordinator).getDetail(reportId);
    }
}
