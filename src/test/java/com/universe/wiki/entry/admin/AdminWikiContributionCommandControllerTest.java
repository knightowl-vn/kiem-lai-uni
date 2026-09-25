package com.universe.wiki.entry.admin;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.wiki.application.contribution.workflow.AdminWikiContributionWorkflowUseCase;
import com.universe.wiki.application.contribution.workflow.ClaimWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.ReassignWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.RejectWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.ResolveWikiContributionCommand;
import com.universe.wiki.application.contribution.workflow.ReviewWikiContributionCommand;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminWikiContributionCommandControllerTest {

    @Mock
    private AdminWikiContributionWorkflowUseCase workflowUseCase;

    private AdminWikiContributionCommandController controller;

    private MockHttpServletRequest request;
    private RedirectAttributesModelMap redirectAttributes;
    private UUID adminUserId;
    private UUID contributionId;

    @BeforeEach
    void setUp() {
        controller = new AdminWikiContributionCommandController(workflowUseCase);
        request = new MockHttpServletRequest();
        redirectAttributes = new RedirectAttributesModelMap();
        adminUserId = UUID.randomUUID();
        contributionId = UUID.randomUUID();

        AuthenticatedRequestIdentity identity = new AuthenticatedRequestIdentity(
                adminUserId,
                "admin@universe.local",
                "Admin User",
                null,
                UserStatus.ACTIVE,
                UserRole.ADMIN
        );
        AuthenticatedRequestIdentityTestSupport.attach(request, identity);
    }

    @Test
    @DisplayName("Constructor enforces non-null workflow use case")
    void constructorEnforcesNonNull() {
        assertThatThrownBy(() -> new AdminWikiContributionCommandController(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AdminWikiContributionWorkflowUseCase cannot be null");
    }

    @Nested
    @DisplayName("POST /{contributionId}/review")
    class ReviewActionTests {

        @Test
        @DisplayName("Missing expectedVersion redirects to detail with error flash attribute")
        void shouldRequireExpectedVersion() {
            String view = controller.reviewContribution(contributionId, null, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_VERSION_REQUIRED);
            verify(workflowUseCase, never()).review(any());
        }

        @Test
        @DisplayName("Unauthenticated request throws AccessDeniedException")
        void shouldThrowAccessDeniedWhenUnauthenticated() {
            MockHttpServletRequest unauthRequest = new MockHttpServletRequest();

            assertThatThrownBy(() -> controller.reviewContribution(contributionId, 1L, unauthRequest, redirectAttributes))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Yêu cầu thông tin định danh quản trị viên hợp lệ.");
            verify(workflowUseCase, never()).review(any());
        }

        @Test
        @DisplayName("Successful review executes command and redirects to detail with success flash message")
        void shouldExecuteReviewSuccessfully() {
            String view = controller.reviewContribution(contributionId, 2L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_SUCCESS_REVIEW);

            ArgumentCaptor<ReviewWikiContributionCommand> captor = ArgumentCaptor.forClass(ReviewWikiContributionCommand.class);
            verify(workflowUseCase).review(captor.capture());

            ReviewWikiContributionCommand cmd = captor.getValue();
            assertThat(cmd.contributionId()).isEqualTo(contributionId);
            assertThat(cmd.actorId()).isEqualTo(adminUserId);
            assertThat(cmd.expectedVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("WikiContributionNotFoundException redirects to inbox with error flash message")
        void shouldRedirectToInboxWhenNotFound() {
            doThrow(new WikiContributionNotFoundException(contributionId))
                    .when(workflowUseCase).review(any());

            String view = controller.reviewContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions");
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains(contributionId.toString());
        }

        @Test
        @DisplayName("WikiContributionStaleMutationException redirects to detail with conflict error flash message")
        void shouldRedirectToDetailOnStaleMutation() {
            doThrow(new WikiContributionStaleMutationException(contributionId, 1L, 2L))
                    .when(workflowUseCase).review(any());

            String view = controller.reviewContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_STALE);
        }

        @Test
        @DisplayName("IllegalStateException redirects to detail with descriptive error flash message")
        void shouldRedirectToDetailOnInvalidStateTransition() {
            doThrow(new IllegalStateException("Chỉ đóng góp ở trạng thái Mới mới có thể chuyển sang Đang xem xét"))
                    .when(workflowUseCase).review(any());

            String view = controller.reviewContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains("Chỉ đóng góp ở trạng thái Mới mới có thể chuyển sang Đang xem xét");
        }
    }

    @Nested
    @DisplayName("POST /{contributionId}/resolve")
    class ResolveActionTests {

        @Test
        @DisplayName("Missing expectedVersion redirects to detail with error flash attribute")
        void shouldRequireExpectedVersion() {
            String view = controller.resolveContribution(contributionId, null, "Hợp lệ và đã xác minh", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_VERSION_REQUIRED);
            verify(workflowUseCase, never()).resolve(any());
        }

        @Test
        @DisplayName("Empty resolution note redirects to detail with error flash attribute")
        void shouldRequireResolutionNote() {
            String view = controller.resolveContribution(contributionId, 1L, "   ", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_NOTE_REQUIRED);
            verify(workflowUseCase, never()).resolve(any());
        }

        @Test
        @DisplayName("Resolution note shorter than 5 chars redirects to detail with bounds error")
        void shouldValidateResolutionNoteMinBounds() {
            String view = controller.resolveContribution(contributionId, 1L, "1234", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_NOTE_BOUNDS);
            verify(workflowUseCase, never()).resolve(any());
        }

        @Test
        @DisplayName("Resolution note longer than 2000 chars redirects to detail with bounds error")
        void shouldValidateResolutionNoteMaxBounds() {
            String longNote = "a".repeat(2001);
            String view = controller.resolveContribution(contributionId, 1L, longNote, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_NOTE_BOUNDS);
            verify(workflowUseCase, never()).resolve(any());
        }

        @Test
        @DisplayName("Successful resolve executes command and redirects to detail with success flash message")
        void shouldExecuteResolveSuccessfully() {
            String note = "Đã bổ sung nội dung xác thực từ chương 100.";
            String view = controller.resolveContribution(contributionId, 3L, note, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_SUCCESS_RESOLVE);

            ArgumentCaptor<ResolveWikiContributionCommand> captor = ArgumentCaptor.forClass(ResolveWikiContributionCommand.class);
            verify(workflowUseCase).resolve(captor.capture());

            ResolveWikiContributionCommand cmd = captor.getValue();
            assertThat(cmd.contributionId()).isEqualTo(contributionId);
            assertThat(cmd.actorId()).isEqualTo(adminUserId);
            assertThat(cmd.expectedVersion()).isEqualTo(3L);
            assertThat(cmd.resolutionNote()).isEqualTo(note);
        }

        @Test
        @DisplayName("WikiContributionNotFoundException redirects to inbox with error flash message")
        void shouldRedirectToInboxWhenNotFound() {
            doThrow(new WikiContributionNotFoundException(contributionId))
                    .when(workflowUseCase).resolve(any());

            String view = controller.resolveContribution(contributionId, 1L, "Đã xử lý xong", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions");
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains(contributionId.toString());
        }

        @Test
        @DisplayName("WikiContributionStaleMutationException redirects to detail with conflict error flash message")
        void shouldRedirectToDetailOnStaleMutation() {
            doThrow(new WikiContributionStaleMutationException(contributionId, 1L, 2L))
                    .when(workflowUseCase).resolve(any());

            String view = controller.resolveContribution(contributionId, 1L, "Đã xử lý xong", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_STALE);
        }
    }

    @Nested
    @DisplayName("POST /{contributionId}/reject")
    class RejectActionTests {

        @Test
        @DisplayName("Missing expectedVersion redirects to detail with error flash attribute")
        void shouldRequireExpectedVersion() {
            String view = controller.rejectContribution(contributionId, null, "Không đúng nguyên tác", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_VERSION_REQUIRED);
            verify(workflowUseCase, never()).reject(any());
        }

        @Test
        @DisplayName("Empty resolution note redirects to detail with error flash attribute")
        void shouldRequireResolutionNote() {
            String view = controller.rejectContribution(contributionId, 1L, "   ", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_NOTE_REQUIRED);
            verify(workflowUseCase, never()).reject(any());
        }

        @Test
        @DisplayName("Resolution note shorter than 5 chars redirects to detail with bounds error")
        void shouldValidateResolutionNoteMinBounds() {
            String view = controller.rejectContribution(contributionId, 1L, "sai", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_NOTE_BOUNDS);
            verify(workflowUseCase, never()).reject(any());
        }

        @Test
        @DisplayName("Successful reject executes command and redirects to detail with success flash message")
        void shouldExecuteRejectSuccessfully() {
            String note = "Nội dung đóng góp không phù hợp với thiết lập nguyên tác.";
            String view = controller.rejectContribution(contributionId, 2L, note, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_SUCCESS_REJECT);

            ArgumentCaptor<RejectWikiContributionCommand> captor = ArgumentCaptor.forClass(RejectWikiContributionCommand.class);
            verify(workflowUseCase).reject(captor.capture());

            RejectWikiContributionCommand cmd = captor.getValue();
            assertThat(cmd.contributionId()).isEqualTo(contributionId);
            assertThat(cmd.actorId()).isEqualTo(adminUserId);
            assertThat(cmd.expectedVersion()).isEqualTo(2L);
            assertThat(cmd.resolutionNote()).isEqualTo(note);
        }

        @Test
        @DisplayName("WikiContributionNotFoundException redirects to inbox with error flash message")
        void shouldRedirectToInboxWhenNotFound() {
            doThrow(new WikiContributionNotFoundException(contributionId))
                    .when(workflowUseCase).reject(any());

            String view = controller.rejectContribution(contributionId, 1L, "Từ chối vì trùng lặp", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions");
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains(contributionId.toString());
        }

        @Test
        @DisplayName("WikiContributionStaleMutationException redirects to detail with conflict error flash message")
        void shouldRedirectToDetailOnStaleMutation() {
            doThrow(new WikiContributionStaleMutationException(contributionId, 1L, 2L))
                    .when(workflowUseCase).reject(any());

            String view = controller.rejectContribution(contributionId, 1L, "Từ chối vì trùng lặp", request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_STALE);
        }
    }

    @Nested
    @DisplayName("POST /{contributionId}/claim")
    class ClaimActionTests {

        @Test
        @DisplayName("Missing expectedVersion redirects to detail with error flash attribute")
        void shouldRequireExpectedVersion() {
            String view = controller.claimContribution(contributionId, null, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_VERSION_REQUIRED);
            verify(workflowUseCase, never()).claim(any());
        }

        @Test
        @DisplayName("Successful claim executes command and redirects to detail with success flash message")
        void shouldExecuteClaimSuccessfully() {
            String view = controller.claimContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_SUCCESS_CLAIM);

            ArgumentCaptor<ClaimWikiContributionCommand> captor = ArgumentCaptor.forClass(ClaimWikiContributionCommand.class);
            verify(workflowUseCase).claim(captor.capture());

            ClaimWikiContributionCommand cmd = captor.getValue();
            assertThat(cmd.contributionId()).isEqualTo(contributionId);
            assertThat(cmd.actorId()).isEqualTo(adminUserId);
            assertThat(cmd.expectedVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("WikiContributionNotFoundException redirects to inbox with error flash message")
        void shouldRedirectToInboxWhenNotFound() {
            doThrow(new WikiContributionNotFoundException(contributionId))
                    .when(workflowUseCase).claim(any());

            String view = controller.claimContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions");
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains(contributionId.toString());
        }

        @Test
        @DisplayName("WikiContributionStaleMutationException redirects to detail with conflict error flash message")
        void shouldRedirectToDetailOnStaleMutation() {
            doThrow(new WikiContributionStaleMutationException(contributionId, 1L, 2L))
                    .when(workflowUseCase).claim(any());

            String view = controller.claimContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_STALE);
        }

        @Test
        @DisplayName("IllegalStateException redirects to detail with descriptive error flash message")
        void shouldRedirectToDetailOnInvalidStateTransition() {
            doThrow(new IllegalStateException("Đóng góp đã có người phụ trách"))
                    .when(workflowUseCase).claim(any());

            String view = controller.claimContribution(contributionId, 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains("Đóng góp đã có người phụ trách");
        }
    }

    @Nested
    @DisplayName("POST /{contributionId}/reassign")
    class ReassignActionTests {

        private UUID targetUserId;

        @BeforeEach
        void setUpReassign() {
            targetUserId = UUID.randomUUID();
        }

        @Test
        @DisplayName("Missing expectedVersion redirects to detail with error flash attribute")
        void shouldRequireExpectedVersion() {
            String view = controller.reassignContribution(contributionId, targetUserId, "Lý do hợp lệ để phân công", null, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_ERROR_VERSION_REQUIRED);
            verify(workflowUseCase, never()).reassign(any());
        }

        @Test
        @DisplayName("Missing targetUserId redirects to detail with error flash attribute")
        void shouldRequireTargetUserId() {
            String view = controller.reassignContribution(contributionId, null, "Lý do hợp lệ để phân công", 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                    .isEqualTo("Vui lòng chọn quản trị viên nhận phân công.");
            verify(workflowUseCase, never()).reassign(any());
        }

        @Test
        @DisplayName("Invalid reason redirects to detail with error flash attribute")
        void shouldValidateReasonBounds() {
            String view = controller.reassignContribution(contributionId, targetUserId, "ngan", 1L, request, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                    .contains("Lý do phân công lại phải từ");
            verify(workflowUseCase, never()).reassign(any());
        }

        @Test
        @DisplayName("ADMIN role is denied access; SUPER_ADMIN is required")
        void shouldThrowAccessDeniedWhenNotSuperAdmin() {
            // request identity is ADMIN (set up in @BeforeEach)
            assertThatThrownBy(() -> controller.reassignContribution(contributionId, targetUserId, "Lý do hợp lệ để chuyển", 1L, request, redirectAttributes))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("SUPER_ADMIN");
            verify(workflowUseCase, never()).reassign(any());
        }

        @Test
        @DisplayName("SUPER_ADMIN can reassign successfully")
        void shouldExecuteReassignSuccessfullyForSuperAdmin() {
            MockHttpServletRequest superAdminRequest = new MockHttpServletRequest();
            AuthenticatedRequestIdentity superAdminIdentity = new AuthenticatedRequestIdentity(
                    adminUserId,
                    "superadmin@universe.local",
                    "Super Admin",
                    null,
                    UserStatus.ACTIVE,
                    UserRole.SUPER_ADMIN
            );
            AuthenticatedRequestIdentityTestSupport.attach(superAdminRequest, superAdminIdentity);

            String reason = "Phân công lại cho chuyên gia phụ trách tuyến nhân vật này.";
            String view = controller.reassignContribution(contributionId, targetUserId, reason, 2L, superAdminRequest, redirectAttributes);

            assertThat(view).isEqualTo("redirect:/admin/wiki/contributions/" + contributionId);
            assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                    .isEqualTo(AdminWikiContributionCommandController.FLASH_SUCCESS_REASSIGN);

            ArgumentCaptor<ReassignWikiContributionCommand> captor = ArgumentCaptor.forClass(ReassignWikiContributionCommand.class);
            verify(workflowUseCase).reassign(captor.capture());

            ReassignWikiContributionCommand cmd = captor.getValue();
            assertThat(cmd.contributionId()).isEqualTo(contributionId);
            assertThat(cmd.actorId()).isEqualTo(adminUserId);
            assertThat(cmd.targetUserId()).isEqualTo(targetUserId);
            assertThat(cmd.reason()).isEqualTo(reason);
            assertThat(cmd.expectedVersion()).isEqualTo(2L);
        }
    }
}
