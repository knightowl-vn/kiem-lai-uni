package com.universe.interaction.entry.admin;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.interaction.application.exceptions.CommentNotFoundException;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.mutation.ReportModerationAction;
import com.universe.interaction.application.mutation.ResolveCommentReportCommand;
import com.universe.interaction.application.mutation.ResolveCommentReportUseCase;
import com.universe.interaction.domain.report.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCommentReportModerationController Unit Tests")
class AdminCommentReportModerationControllerTest {

    @Mock
    private ResolveCommentReportUseCase resolveCommentReportUseCase;

    private AdminCommentReportModerationController controller;

    private static final UUID REPORT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODERATOR_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        controller = new AdminCommentReportModerationController(resolveCommentReportUseCase);
    }

    private void attachAdminIdentity(MockHttpServletRequest request) {
        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                MODERATOR_USER_ID,
                "admin@universe.local",
                "Moderator Admin",
                null,
                UserStatus.ACTIVE,
                UserRole.ADMIN
        );
        AuthenticatedRequestIdentityTestSupport.attach(request, identity);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Constructor should reject null ResolveCommentReportUseCase")
        void shouldRejectNullUseCase() {
            assertThatThrownBy(() -> new AdminCommentReportModerationController(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ResolveCommentReportUseCase cannot be null");
        }
    }

    @Nested
    @DisplayName("Successful Moderation Mutation Tests")
    class SuccessMutationTests {

        @Test
        @DisplayName("Case D: DELETE_COMMENT string parses correctly, use case invoked once, success flash and redirect to detail")
        void shouldSuccessfullyModerateDeleteComment() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.resolveReport(
                    REPORT_ID,
                    "DELETE_COMMENT",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_SUCCESS_DELETE);

            ArgumentCaptor<ResolveCommentReportCommand> commandCaptor =
                    ArgumentCaptor.forClass(ResolveCommentReportCommand.class);
            verify(resolveCommentReportUseCase).execute(commandCaptor.capture());

            ResolveCommentReportCommand captured = commandCaptor.getValue();
            assertThat(captured.reportId()).isEqualTo(REPORT_ID);
            assertThat(captured.moderatorUserId()).isEqualTo(MODERATOR_USER_ID);
            assertThat(captured.action()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
        }

        @Test
        @DisplayName("Case E: NO_ACTION string parses correctly, use case invoked once, success flash and redirect to detail")
        void shouldSuccessfullyModerateNoAction() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.resolveReport(
                    REPORT_ID,
                    "NO_ACTION",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_SUCCESS_NO_ACTION);

            ArgumentCaptor<ResolveCommentReportCommand> commandCaptor =
                    ArgumentCaptor.forClass(ResolveCommentReportCommand.class);
            verify(resolveCommentReportUseCase).execute(commandCaptor.capture());

            ResolveCommentReportCommand captured = commandCaptor.getValue();
            assertThat(captured.reportId()).isEqualTo(REPORT_ID);
            assertThat(captured.moderatorUserId()).isEqualTo(MODERATOR_USER_ID);
            assertThat(captured.action()).isEqualTo(ReportModerationAction.NO_ACTION);
        }
    }

    @Nested
    @DisplayName("Error Mapping Tests")
    class ErrorMappingTests {

        @Test
        @DisplayName("InteractionReportNotFoundException redirects to report queue with error flash")
        void shouldHandleReportNotFound() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            doThrow(new InteractionReportNotFoundException(REPORT_ID))
                    .when(resolveCommentReportUseCase).execute(any());

            String view = controller.resolveReport(
                    REPORT_ID,
                    "DELETE_COMMENT",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports");
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains("Không tìm thấy báo cáo: " + REPORT_ID);
        }

        @Test
        @DisplayName("ReportAlreadyResolvedException redirects to report detail with error flash")
        void shouldHandleReportAlreadyResolved() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            doThrow(new ReportAlreadyResolvedException(REPORT_ID, ReportStatus.RESOLVED_ACTION_TAKEN))
                    .when(resolveCommentReportUseCase).execute(any());

            String view = controller.resolveReport(
                    REPORT_ID,
                    "DELETE_COMMENT",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_ERROR_ALREADY_RESOLVED);
        }

        @Test
        @DisplayName("CommentNotFoundException during DELETE_COMMENT redirects to report detail with error flash")
        void shouldHandleCommentNotFound() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            doThrow(new CommentNotFoundException(COMMENT_ID))
                    .when(resolveCommentReportUseCase).execute(any());

            String view = controller.resolveReport(
                    REPORT_ID,
                    "DELETE_COMMENT",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_ERROR_COMMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("Unexpected RuntimeException propagates without swallowing")
        void shouldPropagateUnexpectedRuntimeException() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            doThrow(new IllegalStateException("Unexpected infrastructure error"))
                    .when(resolveCommentReportUseCase).execute(any());

            assertThatThrownBy(() -> controller.resolveReport(
                    REPORT_ID,
                    "DELETE_COMMENT",
                    request,
                    redirectAttributes
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unexpected infrastructure error");
        }
    }

    @Nested
    @DisplayName("Identity and Raw Action Parsing Tests")
    class IdentityAndParsingTests {

        @Test
        @DisplayName("Missing authenticated identity throws AccessDeniedException and never invokes use case")
        void shouldThrowAccessDeniedWhenIdentityMissing() {
            MockHttpServletRequest unauthenticatedRequest = new MockHttpServletRequest();
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            assertThatThrownBy(() -> controller.resolveReport(
                    REPORT_ID,
                    "DELETE_COMMENT",
                    unauthenticatedRequest,
                    redirectAttributes
            ))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Yêu cầu thông tin định danh quản trị viên hợp lệ.");

            verify(resolveCommentReportUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Case A: null action string rejects, use case never invoked, flash error, detail redirect")
        void shouldRejectNullActionString() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.resolveReport(
                    REPORT_ID,
                    null,
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_ERROR_INVALID_ACTION);

            verify(resolveCommentReportUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Case B: blank action string rejects, use case never invoked, flash error, detail redirect")
        void shouldRejectBlankActionString() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.resolveReport(
                    REPORT_ID,
                    "    ",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_ERROR_INVALID_ACTION);

            verify(resolveCommentReportUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Case C1: unknown action string 'DELETE_ALL' rejects, use case never invoked, flash error, detail redirect")
        void shouldRejectUnknownActionString() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.resolveReport(
                    REPORT_ID,
                    "DELETE_ALL",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_ERROR_INVALID_ACTION);

            verify(resolveCommentReportUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Case C2: lower-case 'delete_comment' rejects (case-sensitive), use case never invoked, flash error, detail redirect")
        void shouldRejectCaseInsensitiveActionString() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            attachAdminIdentity(request);
            RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

            String view = controller.resolveReport(
                    REPORT_ID,
                    "delete_comment",
                    request,
                    redirectAttributes
            );

            assertThat(view).isEqualTo("redirect:/admin/comments/reports/" + REPORT_ID);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminCommentReportModerationController.FLASH_ERROR_INVALID_ACTION);

            verify(resolveCommentReportUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Malformed UUID path variable does not route through action error handler")
        void shouldNotTreatMalformedUuidAsInvalidAction() throws Exception {
            MockMvc standaloneMockMvc = MockMvcBuilders.standaloneSetup(controller).build();

            standaloneMockMvc.perform(post("/admin/comments/reports/not-a-uuid/resolve")
                            .param("action", "DELETE_COMMENT"))
                    .andExpect(status().isBadRequest());
        }
    }
}
