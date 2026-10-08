package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.AddScreenFranchiseMemberCommand;
import com.universe.screen.application.commands.CreateScreenFranchiseCommand;
import com.universe.screen.application.commands.RemoveScreenFranchiseMemberCommand;
import com.universe.screen.application.commands.UpdateScreenFranchiseCommand;
import com.universe.screen.application.commands.UpdateScreenFranchiseMemberOrderCommand;
import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException;
import com.universe.screen.application.exceptions.ScreenFranchiseMembershipNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseNotFoundException;
import com.universe.screen.application.exceptions.ScreenFranchiseSlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.AddScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.CreateScreenFranchiseUseCase;
import com.universe.screen.application.usecase.RemoveScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseMemberOrderUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseUseCase;
import com.universe.screen.entry.admin.form.AddScreenFranchiseMemberForm;
import com.universe.screen.entry.admin.form.CreateScreenFranchiseForm;
import com.universe.screen.entry.admin.form.EditScreenFranchiseForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScreenFranchiseCommandControllerTest {

    @Mock
    private CreateScreenFranchiseUseCase createScreenFranchiseUseCase;

    @Mock
    private UpdateScreenFranchiseUseCase updateScreenFranchiseUseCase;

    @Mock
    private AddScreenFranchiseMemberUseCase addScreenFranchiseMemberUseCase;

    @Mock
    private UpdateScreenFranchiseMemberOrderUseCase updateScreenFranchiseMemberOrderUseCase;

    @Mock
    private RemoveScreenFranchiseMemberUseCase removeScreenFranchiseMemberUseCase;

    private AdminScreenFranchiseCommandController controller;

    private final UUID franchiseId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminScreenFranchiseCommandController(
                createScreenFranchiseUseCase,
                updateScreenFranchiseUseCase,
                addScreenFranchiseMemberUseCase,
                updateScreenFranchiseMemberOrderUseCase,
                removeScreenFranchiseMemberUseCase
        );
    }

    @Test
    @DisplayName("Command: createFranchise with valid input redirects to memberships with successMessage")
    void shouldCreateFranchiseAndRedirectToMemberships() {
        CreateScreenFranchiseForm form = new CreateScreenFranchiseForm("Kiếm Lai", "kiem-lai");
        ScreenFranchiseDTO created = new ScreenFranchiseDTO(franchiseId, "Kiếm Lai", "kiem-lai", 0);
        when(createScreenFranchiseUseCase.execute(any(CreateScreenFranchiseCommand.class))).thenReturn(created);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createFranchise(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
    }

    @Test
    @DisplayName("Command: createFranchise with blank fields redirects to /new with errorMessage")
    void shouldRedirectToNewOnBlankCreateForm() {
        CreateScreenFranchiseForm form = new CreateScreenFranchiseForm("", "kiem-lai");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createFranchise(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/new");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: createFranchise with duplicate slug redirects to /new with errorMessage")
    void shouldRedirectToNewOnDuplicateSlug() {
        CreateScreenFranchiseForm form = new CreateScreenFranchiseForm("Kiếm Lai", "kiem-lai");
        when(createScreenFranchiseUseCase.execute(any(CreateScreenFranchiseCommand.class)))
                .thenThrow(new ScreenFranchiseSlugAlreadyExistsException("kiem-lai"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createFranchise(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/new");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Slug franchise \"kiem-lai\" đã tồn tại trên hệ thống.");
    }

    @Test
    @DisplayName("Command: createFranchise persistence race conflict redirects to /new with errorMessage")
    void shouldRedirectToNewOnCreatePersistenceRaceConflict() {
        CreateScreenFranchiseForm form = new CreateScreenFranchiseForm("Kiếm Lai", "kiem-lai");
        when(createScreenFranchiseUseCase.execute(any(CreateScreenFranchiseCommand.class)))
                .thenThrow(new ScreenFranchiseSlugAlreadyExistsException("kiem-lai", new RuntimeException("DB duplicate key uq_screen_franchises_slug")));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createFranchise(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/new");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Slug franchise \"kiem-lai\" đã tồn tại trên hệ thống.");
    }

    @Test
    @DisplayName("Command: updateFranchise with valid input redirects to memberships with successMessage")
    void shouldUpdateFranchiseAndRedirect() {
        EditScreenFranchiseForm form = new EditScreenFranchiseForm("Kiếm Lai Mới", "kiem-lai-moi");
        ScreenFranchiseDTO updated = new ScreenFranchiseDTO(franchiseId, "Kiếm Lai Mới", "kiem-lai-moi", 0);
        when(updateScreenFranchiseUseCase.execute(any(UpdateScreenFranchiseCommand.class))).thenReturn(updated);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateFranchise(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
    }

    @Test
    @DisplayName("Command: updateFranchise with duplicate slug redirects to edit with errorMessage")
    void shouldRedirectToEditOnDuplicateSlug() {
        EditScreenFranchiseForm form = new EditScreenFranchiseForm("Kiếm Lai Mới", "kiem-lai-moi");
        when(updateScreenFranchiseUseCase.execute(any(UpdateScreenFranchiseCommand.class)))
                .thenThrow(new ScreenFranchiseSlugAlreadyExistsException("kiem-lai-moi"));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateFranchise(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: updateFranchise persistence race conflict redirects to edit with errorMessage")
    void shouldRedirectToEditOnUpdatePersistenceRaceConflict() {
        EditScreenFranchiseForm form = new EditScreenFranchiseForm("Kiếm Lai Mới", "kiem-lai-moi");
        when(updateScreenFranchiseUseCase.execute(any(UpdateScreenFranchiseCommand.class)))
                .thenThrow(new ScreenFranchiseSlugAlreadyExistsException("kiem-lai-moi", new RuntimeException("DB duplicate key uq_screen_franchises_slug")));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateFranchise(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/edit");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage"))
                .isEqualTo("Slug franchise \"kiem-lai-moi\" đã tồn tại trên hệ thống.");
    }

    @Test
    @DisplayName("Command: updateFranchise missing franchise redirects to /admin/screen/franchises with errorMessage")
    void shouldRedirectToListWhenUpdatingMissingFranchise() {
        EditScreenFranchiseForm form = new EditScreenFranchiseForm("Kiếm Lai", "kiem-lai");
        when(updateScreenFranchiseUseCase.execute(any(UpdateScreenFranchiseCommand.class)))
                .thenThrow(new ScreenFranchiseNotFoundException(franchiseId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateFranchise(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: addMember valid redirects to memberships with successMessage")
    void shouldAddMemberSuccessfully() {
        AddScreenFranchiseMemberForm form = new AddScreenFranchiseMemberForm(titleId, 1);
        ScreenFranchiseMemberDTO dto = new ScreenFranchiseMemberDTO(
                membershipId, franchiseId, titleId, "Part 1", "part-1", 1, null, null, null
        );
        when(addScreenFranchiseMemberUseCase.execute(any(AddScreenFranchiseMemberCommand.class))).thenReturn(dto);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.addMember(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã thêm tiêu đề \"Part 1\" vào franchise.");
    }

    @Test
    @DisplayName("Command: addMember duplicate membership catches DuplicateScreenFranchiseMembershipException and sets errorMessage without raw error")
    void shouldHandleDuplicateMembershipConflictCleanly() {
        AddScreenFranchiseMemberForm form = new AddScreenFranchiseMemberForm(titleId, 0);
        when(addScreenFranchiseMemberUseCase.execute(any(AddScreenFranchiseMemberCommand.class)))
                .thenThrow(new DuplicateScreenFranchiseMembershipException(franchiseId, titleId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.addMember(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                .contains(titleId.toString());
    }

    @Test
    @DisplayName("Command: addMember persistence race duplicate membership catches DuplicateScreenFranchiseMembershipException and sets errorMessage")
    void shouldHandleAddMemberPersistenceRaceConflictCleanly() {
        AddScreenFranchiseMemberForm form = new AddScreenFranchiseMemberForm(titleId, 0);
        when(addScreenFranchiseMemberUseCase.execute(any(AddScreenFranchiseMemberCommand.class)))
                .thenThrow(new DuplicateScreenFranchiseMembershipException(franchiseId, titleId, new RuntimeException("DB duplicate key uq_screen_franchise_memberships_franchise_title")));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.addMember(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat(redirectAttributes.getFlashAttributes().get("errorMessage").toString())
                .contains(titleId.toString());
    }

    @Test
    @DisplayName("Command: addMember missing title sets errorMessage")
    void shouldHandleMissingTitleOnAddMember() {
        AddScreenFranchiseMemberForm form = new AddScreenFranchiseMemberForm(titleId, 0);
        when(addScreenFranchiseMemberUseCase.execute(any(AddScreenFranchiseMemberCommand.class)))
                .thenThrow(new ScreenTitleNotFoundException(titleId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.addMember(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: addMember negative displayOrder sets errorMessage")
    void shouldRejectNegativeDisplayOrderOnAddMember() {
        AddScreenFranchiseMemberForm form = new AddScreenFranchiseMemberForm(titleId, -1);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.addMember(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: updateMemberOrder valid updates displayOrder and redirects")
    void shouldUpdateMemberOrderSuccessfully() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateMemberOrder(franchiseId, membershipId, 3, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes().get("successMessage"))
                .isEqualTo("Đã cập nhật thứ tự hiển thị thành 3.");
        verify(updateScreenFranchiseMemberOrderUseCase).execute(
                new UpdateScreenFranchiseMemberOrderCommand(franchiseId, membershipId, 3)
        );
    }

    @Test
    @DisplayName("Command: updateMemberOrder negative displayOrder sets errorMessage")
    void shouldRejectNegativeDisplayOrderOnUpdate() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateMemberOrder(franchiseId, membershipId, -1, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: removeMember valid deletes membership and redirects with successMessage")
    void shouldRemoveMemberSuccessfully() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.removeMember(franchiseId, membershipId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("successMessage");
        verify(removeScreenFranchiseMemberUseCase).execute(
                new RemoveScreenFranchiseMemberCommand(franchiseId, membershipId)
        );
    }

    @Test
    @DisplayName("Command: removeMember when membership not found sets errorMessage")
    void shouldHandleMissingMemberOnRemove() {
        doThrow(new ScreenFranchiseMembershipNotFoundException(membershipId))
                .when(removeScreenFranchiseMemberUseCase).execute(any(RemoveScreenFranchiseMemberCommand.class));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.removeMember(franchiseId, membershipId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: updateMemberOrder with foreign membership sets errorMessage and preserves membership")
    void shouldHandleForeignMembershipOnOrderUpdate() {
        when(updateScreenFranchiseMemberOrderUseCase.execute(any(UpdateScreenFranchiseMemberOrderCommand.class)))
                .thenThrow(new ScreenFranchiseMembershipNotFoundException(membershipId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateMemberOrder(franchiseId, membershipId, 5, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                .contains(membershipId.toString());
    }

    @Test
    @DisplayName("Command: removeMember with foreign membership sets errorMessage")
    void shouldHandleForeignMembershipOnRemove() {
        doThrow(new ScreenFranchiseMembershipNotFoundException(membershipId))
                .when(removeScreenFranchiseMemberUseCase).execute(any(RemoveScreenFranchiseMemberCommand.class));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.removeMember(franchiseId, membershipId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises/" + franchiseId + "/memberships");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
        assertThat((String) redirectAttributes.getFlashAttributes().get("errorMessage"))
                .contains(membershipId.toString());
    }

    @Test
    @DisplayName("Command: updateMemberOrder when franchise not found redirects to franchise list")
    void shouldRedirectToListWhenFranchiseNotFoundOnOrderUpdate() {
        when(updateScreenFranchiseMemberOrderUseCase.execute(any(UpdateScreenFranchiseMemberOrderCommand.class)))
                .thenThrow(new ScreenFranchiseNotFoundException(franchiseId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateMemberOrder(franchiseId, membershipId, 1, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: removeMember when franchise not found redirects to franchise list")
    void shouldRedirectToListWhenFranchiseNotFoundOnRemove() {
        doThrow(new ScreenFranchiseNotFoundException(franchiseId))
                .when(removeScreenFranchiseMemberUseCase).execute(any(RemoveScreenFranchiseMemberCommand.class));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.removeMember(franchiseId, membershipId, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    @DisplayName("Command: addMember when franchise not found redirects to franchise list")
    void shouldRedirectToListWhenFranchiseNotFoundOnAddMember() {
        AddScreenFranchiseMemberForm form = new AddScreenFranchiseMemberForm(titleId, 0);
        when(addScreenFranchiseMemberUseCase.execute(any(AddScreenFranchiseMemberCommand.class)))
                .thenThrow(new ScreenFranchiseNotFoundException(franchiseId));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.addMember(franchiseId, form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/screen/franchises");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("errorMessage");
    }
}
