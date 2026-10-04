package com.universe.community.entry.admin;

import com.universe.community.application.command.RestoreCommunityPostCommand;
import com.universe.community.application.usecase.RestoreCommunityPostUseCase;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenPageDTO;
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
@DisplayName("AdminCommunityPostHiddenController Tests")
class AdminCommunityPostHiddenControllerTest {

    @Mock
    private AdminCommunityPostReviewCoordinator coordinator;

    @Mock
    private RestoreCommunityPostUseCase restoreUseCase;

    private AdminCommunityPostHiddenController controller;

    private final UUID postId = UUID.randomUUID();
    private final UUID moderatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminCommunityPostHiddenController(coordinator, restoreUseCase);
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
    @DisplayName("Renders hidden posts view with default parameters")
    void shouldRenderHiddenPosts() {
        ExtendedModelMap model = new ExtendedModelMap();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AdminCommunityPostHiddenPageDTO mockPage = new AdminCommunityPostHiddenPageDTO(List.of(), 0, 20, 0L);
        when(coordinator.getHiddenPosts(0, 20)).thenReturn(mockPage);

        String view = controller.hiddenPosts(0, 20, model, response);

        assertThat(view).isEqualTo("admin/community/hidden-posts");
        assertThat(model.get("postPage")).isEqualTo(mockPage);
        assertThat(model.get("pageTitle")).isEqualTo("Quản lý cộng đồng");
        assertThat(model.get("pageSubtitle")).isEqualTo("Theo dõi, xét duyệt và xử lý nội dung cộng đồng.");
        assertThat(model.get("activeMenu")).isEqualTo("community-hidden");
        assertThat(response.getHeader("Cache-Control")).contains("no-cache");
    }

    @Test
    @DisplayName("Restores hidden post and sets flash success")
    void shouldRestorePost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.restorePost(postId, "Post reviewed and restored", request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/posts/hidden");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage")).isEqualTo("Đã khôi phục bài viết thành công.");

        ArgumentCaptor<RestoreCommunityPostCommand> captor = ArgumentCaptor.forClass(RestoreCommunityPostCommand.class);
        verify(restoreUseCase).execute(captor.capture());
        assertThat(captor.getValue().postId()).isEqualTo(postId);
        assertThat(captor.getValue().moderatorUserId()).isEqualTo(moderatorId);
        assertThat(captor.getValue().reason()).isEqualTo("Post reviewed and restored");
    }

    @Test
    @DisplayName("Handles post not found on restore with flash error")
    void shouldHandleNotFoundOnRestore() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        doThrow(new CommunityPostNotFoundException(postId)).when(restoreUseCase).execute(new RestoreCommunityPostCommand(postId, moderatorId, null));

        String view = controller.restorePost(postId, null, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/posts/hidden");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage")).isEqualTo("Không tìm thấy bài viết: " + postId);
    }
}
