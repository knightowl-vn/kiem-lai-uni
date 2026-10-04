package com.universe.community.entry.admin;

import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.moderation.CommunityPostModerationAction;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenItemDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostModerationEventDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingItemDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportDetailDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportItemDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostUserDTO;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.infrastructure.security.SafeReturnToValidator;
import com.universe.community.application.usecase.ApproveCommunityPostUseCase;
import com.universe.community.application.usecase.RejectCommunityPostUseCase;
import com.universe.community.application.usecase.RestoreCommunityPostUseCase;
import com.universe.community.application.usecase.ResolveCommunityPostReportUseCase;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Dedicated rendering contract tests for Admin Community Post Thymeleaf views:
 * - admin/community/reports.html
 * - admin/community/report-detail.html
 * - admin/community/pending-posts.html
 * - admin/community/hidden-posts.html
 * - admin/fragments/sidebar.html (DOM links, activeMenu)
 * - CSRF input validation on forms
 */
@WebMvcTest({
        AdminCommunityPostReportQueueController.class,
        AdminCommunityPostReportDetailController.class,
        AdminCommunityPostPendingReviewController.class,
        AdminCommunityPostHiddenController.class
})
@Import({SecurityBeanConfig.class, AdminCommunityPostViewRenderingTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("Admin Community Post View Rendering & UI Contract Tests (MS-07B8.5.3)")
class AdminCommunityPostViewRenderingTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ADMIN_EMAIL = "admin@universe.local";

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public GoogleOAuthSuccessHandler googleOAuthSuccessHandler() {
            return new GoogleOAuthSuccessHandler(Mockito.mock(GoogleOAuthUserService.class));
        }

        @Bean
        public CustomAuthenticationFailureHandler authenticationFailureHandler() {
            return new CustomAuthenticationFailureHandler(new SafeReturnToValidator());
        }

        @Bean
        public AccountStatusFilter accountStatusFilter(SpringDataUserJpaRepository userRepository) {
            return new AccountStatusFilter(
                    com.universe.identity.infrastructure.security.AccountStatusFilterTestSupport
                            .queryPort(userRepository)
            );
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpringDataUserJpaRepository springDataUserJpaRepository;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private AdminCommunityPostReportCoordinator reportCoordinator;

    @MockBean
    private AdminCommunityPostReviewCoordinator reviewCoordinator;

    @MockBean
    private ApproveCommunityPostUseCase approveUseCase;

    @MockBean
    private RejectCommunityPostUseCase rejectUseCase;

    @MockBean
    private RestoreCommunityPostUseCase restoreUseCase;

    @MockBean
    private ResolveCommunityPostReportUseCase resolveReportUseCase;

    @BeforeEach
    void setUp() {
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(ADMIN_ID.toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setDisplayName("Admin User");
        adminEntity.setPublicHandle("admin_user");
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        UserDTO adminUserDto = new UserDTO(ADMIN_ID, ADMIN_EMAIL, "Admin User", null, "admin_user", "ACTIVE", "ADMIN", Instant.now());
        when(authenticatedEmailResolver.require(any())).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminUserDto));
    }

    // =========================================================================
    // 1. REPORTS QUEUE VIEW (admin/community/reports.html)
    // =========================================================================

    @Test
    @DisplayName("Renders report queue view with workspace tabs, filter form, compact thumbnail, and consolidated active sidebar")
    void shouldRenderReportQueueView() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID evidenceMediaAssetId = UUID.randomUUID();

        AdminCommunityPostReportItemDTO item = new AdminCommunityPostReportItemDTO(
                reportId,
                AdminCommunityPostUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png", "alice"),
                ReportReason.SPAM,
                "Looks like spam",
                "Spam post caption text snapshot",
                evidenceMediaAssetId,
                Instant.now(),
                ReportStatus.PENDING,
                postId,
                true,
                CommunityPostStatus.PUBLISHED,
                "Current post caption",
                null,
                AdminCommunityPostUserDTO.resolved(authorId, "Bob Author", "https://img/bob.png", "bob"),
                null,
                null,
                null
        );

        AdminCommunityPostReportPageDTO pageDTO = new AdminCommunityPostReportPageDTO(
                List.of(item), 0, 20, 1L
        );

        when(reportCoordinator.getReportQueue(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(pageDTO);

        mockMvc.perform(get("/admin/community/reports")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                // Unified page identity and subtitle
                .andExpect(content().string(containsString("Quản lý cộng đồng")))
                .andExpect(content().string(containsString("Theo dõi, xét duyệt và xử lý nội dung cộng đồng.")))
                .andExpect(content().string(not(containsString("Quản lý báo cáo bài viết"))))
                .andExpect(content().string(not(containsString("Báo cáo bài viết cộng đồng"))))
                // Exactly ONE canonical top header (no duplicate second heading inside content)
                .andExpect(content().string(not(containsString("community-workspace-heading"))))
                .andExpect(content().string(not(containsString("<h2>Quản lý cộng đồng</h2>"))))
                // Human-readable status filter tabs
                .andExpect(content().string(containsString("Đã ẩn")))
                .andExpect(content().string(containsString("Đã bỏ qua")))
                .andExpect(content().string(not(containsString("Đã ẩn bài"))))
                // Normalized admin button classes
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-primary\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-secondary\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-sm admin-btn-secondary\"")))
                // No table primitive for moderation items; card layout used
                .andExpect(content().string(not(containsString("<table class=\"admin-table\""))))
                .andExpect(content().string(containsString("community-moderation-list")))
                .andExpect(content().string(containsString("community-moderation-card")))
                // Community workspace tabs
                .andExpect(content().string(containsString("community-workspace-tabs")))
                .andExpect(content().string(containsString("href=\"/admin/community/reports\"")))
                .andExpect(content().string(containsString("href=\"/admin/community/posts/pending\"")))
                .andExpect(content().string(containsString("href=\"/admin/community/posts/hidden\"")))
                // Community settings tab
                .andExpect(content().string(containsString("href=\"/admin/community/settings\"")))
                // Reason dropdown options
                .andExpect(content().string(containsString("Spam")))
                .andExpect(content().string(containsString("Quấy rối")))
                .andExpect(content().string(containsString("Phát ngôn thù hằn")))
                // Table columns & content
                .andExpect(content().string(containsString("Alice Reporter")))
                .andExpect(content().string(containsString("Spam post caption text snapshot")))
                .andExpect(content().string(containsString("Bob Author")))
                .andExpect(content().string(containsString("/admin/community/reports/" + reportId)))
                // Compact thumbnail and caption truncation contract
                .andExpect(content().string(containsString("community-compact-thumb-sm")))
                .andExpect(content().string(containsString("/media/assets/" + evidenceMediaAssetId + "/content")))
                .andExpect(content().string(containsString("community-caption-truncate")))
                // Must NOT contain comment-specific or post-mutation action buttons in queue
                .andExpect(content().string(not(containsString("DELETE_COMMENT"))))
                .andExpect(content().string(not(containsString("name=\"action\" value=\"APPROVE\""))))
                .andExpect(content().string(not(containsString("name=\"action\" value=\"REJECT\""))))
                .andExpect(content().string(not(containsString("name=\"action\" value=\"RESTORE\""))))
                // Consolidated single sidebar entry 'Cộng đồng' is active
                .andExpect(content().string(containsString("href=\"/admin/community/reports\" class=\"sidebar-link  active\"")))
                .andExpect(content().string(containsString("<span>Cộng đồng</span>")))
                // Old individual sidebar entries are absent
                .andExpect(content().string(not(containsString("href=\"/admin/community/posts/pending\" class=\"sidebar-link"))))
                .andExpect(content().string(not(containsString("href=\"/admin/community/posts/hidden\" class=\"sidebar-link"))));
    }

    // =========================================================================
    // 2. REPORT DETAIL VIEW (admin/community/report-detail.html)
    // =========================================================================

    @Test
    @DisplayName("Renders report queue view with deduplicated thumbnail when evidence and post image match")
    void shouldRenderReportQueueViewWithDeduplicatedThumbnail() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID sharedMediaAssetId = UUID.randomUUID();

        AdminCommunityPostReportItemDTO item = new AdminCommunityPostReportItemDTO(
                reportId,
                AdminCommunityPostUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png", "alice"),
                ReportReason.SPAM,
                "Looks like spam",
                "Spam caption snapshot",
                sharedMediaAssetId,
                Instant.now(),
                ReportStatus.PENDING,
                postId,
                true,
                CommunityPostStatus.PUBLISHED,
                "Spam caption snapshot",
                sharedMediaAssetId,
                AdminCommunityPostUserDTO.resolved(authorId, "Bob Author", "https://img/bob.png", "bob"),
                null,
                null,
                null
        );

        when(reportCoordinator.getReportQueue(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostReportPageDTO(List.of(item), 0, 20, 1L));

        mockMvc.perform(get("/admin/community/reports")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ảnh bài viết")))
                .andExpect(content().string(not(containsString("Ảnh bằng chứng"))));
    }

    @Test
    @DisplayName("Renders report queue view with both thumbnails when evidence and post image diverge")
    void shouldRenderReportQueueViewWithDivergedThumbnails() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID evidenceMediaAssetId = UUID.randomUUID();
        UUID currentImageMediaAssetId = UUID.randomUUID();

        AdminCommunityPostReportItemDTO item = new AdminCommunityPostReportItemDTO(
                reportId,
                AdminCommunityPostUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png", "alice"),
                ReportReason.SPAM,
                "Looks like spam",
                "Spam caption snapshot",
                evidenceMediaAssetId,
                Instant.now(),
                ReportStatus.PENDING,
                postId,
                true,
                CommunityPostStatus.PUBLISHED,
                "Edited caption",
                currentImageMediaAssetId,
                AdminCommunityPostUserDTO.resolved(authorId, "Bob Author", "https://img/bob.png", "bob"),
                null,
                null,
                null
        );

        when(reportCoordinator.getReportQueue(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostReportPageDTO(List.of(item), 0, 20, 1L));

        mockMvc.perform(get("/admin/community/reports")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ảnh bằng chứng")))
                .andExpect(content().string(containsString("Ảnh bài viết")))
                .andExpect(content().string(containsString("/media/assets/" + evidenceMediaAssetId + "/content")))
                .andExpect(content().string(containsString("/media/assets/" + currentImageMediaAssetId + "/content")));
    }

    // =========================================================================
    // 2. REPORT DETAIL VIEW (admin/community/report-detail.html)
    // =========================================================================

    @Test
    @DisplayName("Renders report detail view when content is UNCHANGED: single post render, unchanged notice, single image, and pending forms")
    void shouldRenderReportDetailViewPendingUnchanged() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();

        AdminCommunityPostReportDetailDTO detailDTO = new AdminCommunityPostReportDetailDTO(
                reportId,
                AdminCommunityPostUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png", "alice"),
                ReportReason.HARASSMENT,
                "Harassment description",
                "Identical post content caption",
                mediaAssetId,
                Instant.now(),
                ReportStatus.PENDING,
                postId,
                true,
                CommunityPostStatus.PUBLISHED,
                "Identical post content caption",
                mediaAssetId,
                AdminCommunityPostUserDTO.resolved(authorId, "Bob Author", "https://img/bob.png", "bob"),
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(1800),
                1,
                null,
                null,
                null,
                List.of()
        );

        when(reportCoordinator.getReportDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/community/reports/{id}", reportId)
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/report-detail"))
                // Title and structure
                .andExpect(content().string(containsString("Chi tiết báo cáo bài viết")))
                .andExpect(content().string(not(containsString("<h2>Chi tiết báo cáo bài viết</h2>"))))
                .andExpect(content().string(containsString("Alice Reporter")))
                .andExpect(content().string(containsString("Bob Author")))
                // Canonical section
                .andExpect(content().string(containsString("Bài viết bị báo cáo")))
                .andExpect(content().string(not(containsString("Bài viết mục tiêu"))))
                // Unchanged difference notice
                .andExpect(content().string(containsString("Nội dung hiện tại không thay đổi kể từ khi được báo cáo.")))
                .andExpect(content().string(containsString("Identical post content caption")))
                .andExpect(content().string(not(containsString("Nội dung lúc bị báo cáo"))))
                .andExpect(content().string(not(containsString("Ảnh lúc bị báo cáo"))))
                // Single image rendered
                .andExpect(content().string(containsString("/media/assets/" + mediaAssetId + "/content")))
                // Public context link
                .andExpect(content().string(containsString("/admin/community/reports/" + reportId + "/context")))
                // Collapsed technical info
                .andExpect(content().string(containsString("admin-tech-details")))
                .andExpect(content().string(containsString("Thông tin kỹ thuật")))
                .andExpect(content().string(containsString(reportId.toString())))
                // Status badge humanized
                .andExpect(content().string(containsString("Đã xuất bản")))
                .andExpect(content().string(not(containsString(">PUBLISHED<"))))
                // Action forms present for PENDING published post with normalized admin-btn
                .andExpect(content().string(containsString("Quyết định xử lý")))
                .andExpect(content().string(containsString("action=\"/admin/community/reports/" + reportId + "/resolve\"")))
                .andExpect(content().string(containsString("value=\"CONTENT_HIDDEN\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-danger\"")))
                .andExpect(content().string(containsString("Ẩn bài viết")))
                .andExpect(content().string(containsString("value=\"NO_ACTION\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-secondary\"")))
                .andExpect(content().string(containsString("Bỏ qua báo cáo")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    @DisplayName("Renders report detail view when content DIVERGED: side-by-side snapshot vs current captions and media")
    void shouldRenderReportDetailViewDivergedContent() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID evidenceMediaAssetId = UUID.randomUUID();
        UUID currentImageMediaAssetId = UUID.randomUUID();

        AdminCommunityPostReportDetailDTO detailDTO = new AdminCommunityPostReportDetailDTO(
                reportId,
                AdminCommunityPostUserDTO.resolved(reporterId, "Alice Reporter", "https://img/alice.png", "alice"),
                ReportReason.SPAM,
                "Spam description",
                "Caption before edit snapshot",
                evidenceMediaAssetId,
                Instant.now(),
                ReportStatus.PENDING,
                postId,
                true,
                CommunityPostStatus.PUBLISHED,
                "Caption after edit current",
                currentImageMediaAssetId,
                AdminCommunityPostUserDTO.resolved(authorId, "Bob Author", "https://img/bob.png", "bob"),
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(1200),
                2,
                null,
                null,
                null,
                List.of()
        );

        when(reportCoordinator.getReportDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/community/reports/{id}", reportId)
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/report-detail"))
                .andExpect(content().string(containsString("Bài viết bị báo cáo")))
                // Unchanged banner must NOT be present
                .andExpect(content().string(not(containsString("Nội dung hiện tại không thay đổi kể từ khi được báo cáo."))))
                // Side-by-side caption diff
                .andExpect(content().string(containsString("Nội dung lúc bị báo cáo")))
                .andExpect(content().string(containsString("Caption before edit snapshot")))
                .andExpect(content().string(containsString("Nội dung hiện tại")))
                .andExpect(content().string(containsString("Caption after edit current")))
                // Side-by-side media diff
                .andExpect(content().string(containsString("Ảnh lúc bị báo cáo")))
                .andExpect(content().string(containsString("/media/assets/" + evidenceMediaAssetId + "/content")))
                .andExpect(content().string(containsString("Ảnh hiện tại")))
                .andExpect(content().string(containsString("/media/assets/" + currentImageMediaAssetId + "/content")));
    }

    @Test
    @DisplayName("Renders report detail view RESOLVED with human-readable moderation timeline and NO action forms")
    void shouldRenderReportDetailViewResolvedWithModerationTimeline() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID modId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();

        AdminCommunityPostModerationEventDTO eventDTO = new AdminCommunityPostModerationEventDTO(
                UUID.randomUUID(),
                postId,
                CommunityPostModerationAction.HIDE,
                CommunityPostStatus.PUBLISHED,
                CommunityPostStatus.HIDDEN,
                AdminCommunityPostUserDTO.resolved(modId, "Moderator Alice", null, "mod_alice"),
                "Vi phạm tiêu chuẩn cộng đồng",
                Instant.now().minusSeconds(1800)
        );

        AdminCommunityPostReportDetailDTO detailDTO = new AdminCommunityPostReportDetailDTO(
                reportId,
                AdminCommunityPostUserDTO.resolved(reporterId, "Bob Reporter", null, "bob_rep"),
                ReportReason.HARASSMENT,
                "Harassment description",
                "Offensive post content caption",
                mediaAssetId,
                Instant.now().minusSeconds(3600),
                ReportStatus.RESOLVED_ACTION_TAKEN,
                postId,
                true,
                CommunityPostStatus.HIDDEN,
                "Offensive post content caption",
                mediaAssetId,
                AdminCommunityPostUserDTO.resolved(authorId, "Charles Author", null, "charles_a"),
                Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(1800),
                1,
                com.universe.interaction.domain.report.ReportModerationAction.CONTENT_HIDDEN,
                AdminCommunityPostUserDTO.resolved(modId, "Moderator Alice", null, "mod_alice"),
                Instant.now().minusSeconds(1800),
                List.of(eventDTO)
        );

        when(reportCoordinator.getReportDetail(reportId)).thenReturn(detailDTO);

        mockMvc.perform(get("/admin/community/reports/{id}", reportId)
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/report-detail"))
                // Resolved status badge and resolver name
                .andExpect(content().string(containsString("Đã ẩn bài viết")))
                .andExpect(content().string(containsString("Xử lý bởi <strong>Moderator Alice</strong>")))
                // Must NOT contain action forms when resolved
                .andExpect(content().string(not(containsString("value=\"CONTENT_HIDDEN\""))))
                .andExpect(content().string(not(containsString("value=\"NO_ACTION\""))))
                // Human-readable moderation timeline
                .andExpect(content().string(containsString("Lịch sử kiểm duyệt bài viết")))
                .andExpect(content().string(containsString("community-timeline")))
                .andExpect(content().string(containsString("Moderator Alice")))
                .andExpect(content().string(containsString("đã ẩn bài viết")))
                .andExpect(content().string(containsString("Lý do:")))
                .andExpect(content().string(containsString("Vi phạm tiêu chuẩn cộng đồng")))
                // No raw table or enum transition in moderation history
                .andExpect(content().string(not(containsString("PUBLISHED → HIDDEN"))));
    }


    // =========================================================================
    // 3. PENDING REVIEW VIEW (admin/community/pending-posts.html)
    // =========================================================================

    @Test
    @DisplayName("Renders pending review view with workspace tabs, APPROVE and REJECT forms, CSRF tokens, and active consolidated sidebar")
    void shouldRenderPendingReviewView() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID imageAssetId = UUID.randomUUID();

        Instant now = Instant.now();
        AdminCommunityPostPendingItemDTO pendingItem = new AdminCommunityPostPendingItemDTO(
                postId,
                AdminCommunityPostUserDTO.resolved(authorId, "Charles Writer", "https://img/charles.png", "charles"),
                "A draft post awaiting approval",
                imageAssetId,
                now,
                now,
                1
        );

        when(reviewCoordinator.getPendingQueue(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostPendingPageDTO(List.of(pendingItem), 0, 20, 1L));

        mockMvc.perform(get("/admin/community/posts/pending")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/pending-posts"))
                // Unified page identity and subtitle
                .andExpect(content().string(containsString("Quản lý cộng đồng")))
                .andExpect(content().string(containsString("Theo dõi, xét duyệt và xử lý nội dung cộng đồng.")))
                .andExpect(content().string(not(containsString("Duyệt bài viết cộng đồng"))))
                .andExpect(content().string(not(containsString("Hàng đợi duyệt bài viết cộng đồng"))))
                // Exactly ONE canonical top header
                .andExpect(content().string(not(containsString("community-workspace-heading"))))
                .andExpect(content().string(not(containsString("<h2>Quản lý cộng đồng</h2>"))))
                // Bounded and centered post-list container exists
                .andExpect(content().string(containsString("admin-community-pending-feed")))
                .andExpect(content().string(containsString("admin-community-post-feed")))
                // Canonical post-card container exists
                .andExpect(content().string(containsString("community-post-card")))
                .andExpect(content().string(containsString("admin-community-post-card")))
                // Avatar / header region exists
                .andExpect(content().string(containsString("post-header")))
                .andExpect(content().string(containsString("post-author-info")))
                .andExpect(content().string(containsString("post-author-avatar")))
                .andExpect(content().string(containsString("Charles Writer")))
                .andExpect(content().string(containsString("@charles")))
                .andExpect(content().string(containsString("post-time")))
                .andExpect(content().string(containsString("Chờ duyệt")))
                // Caption / body region exists
                .andExpect(content().string(containsString("post-caption")))
                .andExpect(content().string(containsString("A draft post awaiting approval")))
                // Image region supported (post preview size, not compact thumb)
                .andExpect(content().string(containsString("post-image-container")))
                .andExpect(content().string(containsString("post-image")))
                .andExpect(content().string(containsString("/media/assets/" + imageAssetId + "/content")))
                // Footer exists with Duyệt and Từ chối
                .andExpect(content().string(containsString("post-footer")))
                .andExpect(content().string(containsString("action=\"/admin/community/posts/" + postId + "/approve\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-sm admin-btn-primary\"")))
                .andExpect(content().string(containsString("Duyệt")))
                .andExpect(content().string(containsString("action=\"/admin/community/posts/" + postId + "/reject\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-sm admin-btn-danger\"")))
                .andExpect(content().string(containsString("Từ chối")))
                // NO reaction button or comment button
                .andExpect(content().string(not(containsString("kl-reaction-widget"))))
                .andExpect(content().string(not(containsString("post-metric--login-link"))))
                .andExpect(content().string(not(containsString("post-comment-toggle-btn"))))
                // NO generic moderation-table/row primitive
                .andExpect(content().string(not(containsString("<table"))))
                .andExpect(content().string(not(containsString("community-moderation-card"))))
                .andExpect(content().string(not(containsString("community-compact-thumb-sm"))))
                .andExpect(content().string(not(containsString("community-caption-truncate"))))
                // CSRF token in forms
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                // Prohibited actions
                .andExpect(content().string(not(containsString("CONTENT_HIDDEN"))))
                .andExpect(content().string(not(containsString("NO_ACTION"))))
                .andExpect(content().string(not(containsString("RESTORE"))))
                // Community workspace tabs
                .andExpect(content().string(containsString("community-workspace-tabs")))
                // Community settings tab
                .andExpect(content().string(containsString("href=\"/admin/community/settings\"")))
                // Consolidated single sidebar entry 'Cộng đồng' is active
                .andExpect(content().string(containsString("href=\"/admin/community/reports\" class=\"sidebar-link  active\"")))
                .andExpect(content().string(containsString("<span>Cộng đồng</span>")))
                // Old separate sidebar links are absent
                .andExpect(content().string(not(containsString("href=\"/admin/community/posts/pending\" class=\"sidebar-link"))));
    }

    @Test
    @DisplayName("Renders pending review view for text-only post without image container")
    void shouldRenderPendingReviewViewForTextOnlyPostWithoutImageContainer() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        AdminCommunityPostPendingItemDTO textOnlyItem = new AdminCommunityPostPendingItemDTO(
                postId,
                AdminCommunityPostUserDTO.resolved(authorId, "Diana Text", "https://img/diana.png", "diana"),
                "Text-only draft post awaiting approval",
                null,
                now,
                now,
                1
        );

        when(reviewCoordinator.getPendingQueue(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostPendingPageDTO(List.of(textOnlyItem), 0, 20, 1L));

        mockMvc.perform(get("/admin/community/posts/pending")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/pending-posts"))
                .andExpect(content().string(containsString("admin-community-pending-feed")))
                .andExpect(content().string(containsString("admin-community-post-feed")))
                .andExpect(content().string(containsString("community-post-card")))
                .andExpect(content().string(containsString("Diana Text")))
                .andExpect(content().string(containsString("Text-only draft post awaiting approval")))
                .andExpect(content().string(containsString("post-footer")))
                .andExpect(content().string(not(containsString("post-image-container"))))
                .andExpect(content().string(not(containsString("post-image"))));
    }

    @Test
    @DisplayName("Renders pending review view for pending caption edit with 'Chờ duyệt chỉnh sửa' badge and caption diff")
    void shouldRenderPendingReviewViewForPendingCaptionEdit() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        AdminCommunityPostPendingItemDTO editItem = new AdminCommunityPostPendingItemDTO(
                postId,
                AdminCommunityPostUserDTO.resolved(authorId, "Eve Editor", "https://img/eve.png", "eve"),
                "Current public caption",
                "Proposed new caption awaiting moderation",
                null,
                now.minusSeconds(3600),
                now,
                now.minusSeconds(3600),
                0
        );

        when(reviewCoordinator.getPendingQueue(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostPendingPageDTO(List.of(editItem), 0, 20, 1L));

        mockMvc.perform(get("/admin/community/posts/pending")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/pending-posts"))
                .andExpect(content().string(containsString("Chờ duyệt chỉnh sửa")))
                .andExpect(content().string(containsString("post-caption-diff")))
                .andExpect(content().string(containsString("Nội dung đang hiển thị:")))
                .andExpect(content().string(containsString("Current public caption")))
                .andExpect(content().string(containsString("Nội dung chỉnh sửa mới:")))
                .andExpect(content().string(containsString("Proposed new caption awaiting moderation")));
    }

    @Test
    @DisplayName("Renders pending review empty state card with proper centered message and NO table")
    void shouldRenderPendingReviewEmptyStateView() throws Exception {
        when(reviewCoordinator.getPendingQueue(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostPendingPageDTO(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/admin/community/posts/pending")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/pending-posts"))
                .andExpect(content().string(containsString("Quản lý cộng đồng")))
                .andExpect(content().string(not(containsString("community-workspace-heading"))))
                .andExpect(content().string(containsString("admin-community-pending-feed")))
                .andExpect(content().string(containsString("community-empty-card")))
                .andExpect(content().string(containsString("Không có bài chờ duyệt")))
                .andExpect(content().string(containsString("Hiện không có nội dung nào cần xử lý.")))
                .andExpect(content().string(not(containsString("<table"))));
    }

    @Test
    @DisplayName("Admin community CSS layout contract: pending feed is horizontally centered with max-width and margin-inline auto")
    void shouldDefineCenteredPendingFeedLayoutInCss() throws Exception {
        String css = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/static/css/admin/comment-reports.css"),
                java.nio.charset.StandardCharsets.UTF_8
        );
        org.assertj.core.api.Assertions.assertThat(css).contains(".admin-community-pending-feed");
        org.assertj.core.api.Assertions.assertThat(css).contains("max-width: 720px;");
        org.assertj.core.api.Assertions.assertThat(css).contains("width: 100%;");
        org.assertj.core.api.Assertions.assertThat(css).contains("margin-left: auto;");
        org.assertj.core.api.Assertions.assertThat(css).contains("margin-right: auto;");
    }

    // =========================================================================
    // 4. HIDDEN POSTS VIEW (admin/community/hidden-posts.html)
    // =========================================================================

    @Test
    @DisplayName("Renders hidden posts view with workspace tabs, RESTORE form, moderation history, CSRF tokens, and active consolidated sidebar")
    void shouldRenderHiddenPostsView() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID modId = UUID.randomUUID();
        UUID imageAssetId = UUID.randomUUID();

        AdminCommunityPostModerationEventDTO eventDTO = new AdminCommunityPostModerationEventDTO(
                UUID.randomUUID(),
                postId,
                CommunityPostModerationAction.HIDE,
                CommunityPostStatus.PUBLISHED,
                CommunityPostStatus.HIDDEN,
                AdminCommunityPostUserDTO.resolved(modId, "Moderator One", null, "mod1"),
                "Policy violation",
                Instant.now().minusSeconds(7200)
        );

        AdminCommunityPostHiddenItemDTO hiddenItem = new AdminCommunityPostHiddenItemDTO(
                postId,
                AdminCommunityPostUserDTO.resolved(authorId, "Dave User", "https://img/dave.png", "dave"),
                "This post was hidden by moderation",
                imageAssetId,
                Instant.now().minusSeconds(86400),
                Instant.now().minusSeconds(7200),
                2,
                List.of(eventDTO)
        );

        when(reviewCoordinator.getHiddenPosts(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostHiddenPageDTO(List.of(hiddenItem), 0, 20, 1L));

        mockMvc.perform(get("/admin/community/posts/hidden")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/hidden-posts"))
                // Unified page identity and subtitle
                .andExpect(content().string(containsString("Quản lý cộng đồng")))
                .andExpect(content().string(containsString("Theo dõi, xét duyệt và xử lý nội dung cộng đồng.")))
                .andExpect(content().string(not(containsString("Quản lý bài viết bị ẩn"))))
                // Exactly ONE canonical top header
                .andExpect(content().string(not(containsString("community-workspace-heading"))))
                .andExpect(content().string(not(containsString("<h2>Quản lý cộng đồng</h2>"))))
                // No table primitive for moderation items; card layout used
                .andExpect(content().string(not(containsString("<table class=\"admin-table\""))))
                .andExpect(content().string(containsString("community-moderation-list")))
                .andExpect(content().string(containsString("community-moderation-card")))
                // Community workspace tabs
                .andExpect(content().string(containsString("community-workspace-tabs")))
                // Community settings tab
                .andExpect(content().string(containsString("href=\"/admin/community/settings\"")))
                .andExpect(content().string(containsString("Dave User")))
                .andExpect(content().string(containsString("This post was hidden by moderation")))
                // Compact thumbnail and caption truncation contract
                .andExpect(content().string(containsString("community-compact-thumb-sm")))
                .andExpect(content().string(containsString("community-caption-truncate")))
                .andExpect(content().string(containsString("Policy violation")))
                .andExpect(content().string(containsString("Moderator One")))
                .andExpect(content().string(containsString("Đã ẩn")))
                .andExpect(content().string(not(containsString(">HIDE<"))))
                // Restore form with CSRF and normalized admin-btn
                .andExpect(content().string(containsString("action=\"/admin/community/posts/" + postId + "/restore\"")))
                .andExpect(content().string(containsString("class=\"admin-btn admin-btn-sm admin-btn-primary\"")))
                .andExpect(content().string(containsString("Khôi phục")))
                // CSRF presence
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                // Prohibited actions
                .andExpect(content().string(not(containsString("CONTENT_HIDDEN"))))
                .andExpect(content().string(not(containsString("NO_ACTION"))))
                .andExpect(content().string(not(containsString("APPROVE"))))
                .andExpect(content().string(not(containsString("REJECT"))))
                // Consolidated single sidebar entry 'Cộng đồng' is active
                .andExpect(content().string(containsString("href=\"/admin/community/reports\" class=\"sidebar-link  active\"")))
                .andExpect(content().string(containsString("<span>Cộng đồng</span>")))
                // Old separate sidebar links are absent
                .andExpect(content().string(not(containsString("href=\"/admin/community/posts/hidden\" class=\"sidebar-link"))));
    }
}
