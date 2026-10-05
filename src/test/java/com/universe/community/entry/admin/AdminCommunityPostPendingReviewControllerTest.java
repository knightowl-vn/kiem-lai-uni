package com.universe.community.entry.admin;

import com.universe.community.application.command.ApproveCommunityPostCommand;
import com.universe.community.application.command.RejectCommunityPostCommand;
import com.universe.community.application.usecase.ApproveCommunityPostUseCase;
import com.universe.community.application.usecase.RejectCommunityPostUseCase;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingPageDTO;
import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommunityPostPendingReviewController Tests")
class AdminCommunityPostPendingReviewControllerTest {

    @Mock
    private AdminCommunityPostReviewCoordinator coordinator;

    @Mock
    private ApproveCommunityPostUseCase approveUseCase;

    @Mock
    private RejectCommunityPostUseCase rejectUseCase;

    private AdminCommunityPostPendingReviewController controller;

    private final UUID postId = UUID.randomUUID();
    private final UUID moderatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminCommunityPostPendingReviewController(coordinator, approveUseCase, rejectUseCase);
    }

    private void attachAdminIdentity(MockHttpServletRequest request) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                moderatorId,
                "admin@universe.local",
                "Admin",
                null,
                "admin_handle",
                UserStatus.ACTIVE,
                UserRole.ADMIN
        );
        AuthenticatedRequestIdentityTestSupport.attach(request, identity);
    }

    @Test
    @DisplayName("Renders pending review queue with default parameters")
    void shouldRenderPendingQueue() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommunityPostPendingPageDTO mockPage = new AdminCommunityPostPendingPageDTO(List.of(), 0, 20, 0L);
        when(coordinator.getPendingQueue(0, 20)).thenReturn(mockPage);

        String view = controller.pendingQueue(0, 20, model, response);

        assertThat(view).isEqualTo("admin/community/pending-posts");
        assertThat(model.get("postPage")).isEqualTo(mockPage);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý cộng đồng");
        assertThat(model.get("pageSubtitle")).isEqualTo("Theo dõi, xét duyệt và xử lý nội dung cộng đồng.");
        assertThat(model.get("activeMenu")).isEqualTo("community-pending");
        assertThat(response.getHeader("Cache-Control")).contains("no-cache");
    }

    @Test
    @DisplayName("Approves post and sets flash success")
    void shouldApprovePost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.approvePost(postId, "Approved by admin", request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/posts/pending");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isEqualTo("Đã duyệt bài viết thành công.");

        ArgumentCaptor<ApproveCommunityPostCommand> captor = ArgumentCaptor.forClass(ApproveCommunityPostCommand.class);
        verify(approveUseCase).execute(captor.capture());
        assertThat(captor.getValue().postId()).isEqualTo(postId);
        assertThat(captor.getValue().moderatorUserId()).isEqualTo(moderatorId);
        assertThat(captor.getValue().reason()).isEqualTo("Approved by admin");
    }

    @Test
    @DisplayName("Rejects post and sets flash success")
    void shouldRejectPost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.rejectPost(postId, "Spam detected", request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/posts/pending");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isEqualTo("Đã từ chối bài viết.");

        ArgumentCaptor<RejectCommunityPostCommand> captor = ArgumentCaptor.forClass(RejectCommunityPostCommand.class);
        verify(rejectUseCase).execute(captor.capture());
        assertThat(captor.getValue().postId()).isEqualTo(postId);
        assertThat(captor.getValue().moderatorUserId()).isEqualTo(moderatorId);
        assertThat(captor.getValue().reason()).isEqualTo("Spam detected");
    }

    @Test
    @DisplayName("Handles post not found on approve with flash error")
    void shouldHandleNotFoundOnApprove() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        doThrow(new CommunityPostNotFoundException(postId)).when(approveUseCase).execute(new ApproveCommunityPostCommand(postId, moderatorId, null));

        String view = controller.approvePost(postId, null, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/posts/pending");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isEqualTo("Không tìm thấy bài viết: " + postId);
    }
}
