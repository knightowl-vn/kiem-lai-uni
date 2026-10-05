package com.universe.community.entry.admin;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.domain.report.ReportModerationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommunityPostReportModerationController Tests")
class AdminCommunityPostReportModerationControllerTest {

    @Mock
    private ResolveCommunityPostReportUseCase resolveUseCase;

    private AdminCommunityPostReportModerationController controller;

    private final UUID reportId = UUID.randomUUID();
    private final UUID moderatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminCommunityPostReportModerationController(resolveUseCase);
    }

    private void attachAdminIdentity(MockHttpServletRequest request) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                moderatorId,
                "admin@universe.local",
                "Administrator",
                null,
                "admin_handle",
                UserStatus.ACTIVE,
                UserRole.ADMIN
        );
        AuthenticatedRequestIdentityTestSupport.attach(request, identity);
    }

    @Test
    @DisplayName("Throws AccessDeniedException when unauthenticated")
    void shouldThrowWhenUnauthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        assertThatThrownBy(() -> controller.resolveReport(reportId, "CONTENT_HIDDEN", null, request, redirectAttributes))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Resolves report with CONTENT_HIDDEN and sets flash success")
    void shouldResolveContentHidden() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.resolveReport(reportId, "CONTENT_HIDDEN", "Violates policy", request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/reports/" + reportId);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo(AdminCommunityPostReportModerationController.FLASH_SUCCESS_HIDE);

        ArgumentCaptor<ResolveCommunityPostReportCommand> captor = ArgumentCaptor.forClass(ResolveCommunityPostReportCommand.class);
        verify(resolveUseCase).execute(captor.capture());
        assertThat(captor.getValue().reportId()).isEqualTo(reportId);
        assertThat(captor.getValue().moderatorUserId()).isEqualTo(moderatorId);
        assertThat(captor.getValue().action()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
        assertThat(captor.getValue().reason()).isEqualTo("Violates policy");
    }

    @Test
    @DisplayName("Resolves report with NO_ACTION and sets flash success")
    void shouldResolveNoAction() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.resolveReport(reportId, "NO_ACTION", "No violation", request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/reports/" + reportId);
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo(AdminCommunityPostReportModerationController.FLASH_SUCCESS_NO_ACTION);
    }

    @Test
    @DisplayName("Rejects invalid action with flash error")
    void shouldRejectInvalidAction() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.resolveReport(reportId, "INVALID_ACTION", null, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/reports/" + reportId);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo(AdminCommunityPostReportModerationController.FLASH_ERROR_INVALID_ACTION);
    }

    @Test
    @DisplayName("Handles ReportAlreadyResolvedException with flash error")
    void shouldHandleAlreadyResolved() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        doThrow(new ReportAlreadyResolvedException(reportId, null))
                .when(resolveUseCase).execute(new ResolveCommunityPostReportCommand(reportId, moderatorId, ReportModerationAction.NO_ACTION, null));

        String view = controller.resolveReport(reportId, "NO_ACTION", null, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/reports/" + reportId);
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo(AdminCommunityPostReportModerationController.FLASH_ERROR_ALREADY_RESOLVED);
    }

    @Test
    @DisplayName("Handles InteractionReportNotFoundException with queue redirect")
    void shouldHandleReportNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        attachAdminIdentity(request);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        doThrow(new InteractionReportNotFoundException(reportId))
                .when(resolveUseCase).execute(new ResolveCommunityPostReportCommand(reportId, moderatorId, ReportModerationAction.NO_ACTION, null));

        String view = controller.resolveReport(reportId, "NO_ACTION", null, request, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/community/reports");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Không tìm thấy báo cáo: " + reportId);
    }
}
