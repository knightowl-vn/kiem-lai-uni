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
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/franchises")
public class AdminScreenFranchiseCommandController {

    private final CreateScreenFranchiseUseCase createScreenFranchiseUseCase;
    private final UpdateScreenFranchiseUseCase updateScreenFranchiseUseCase;
    private final AddScreenFranchiseMemberUseCase addScreenFranchiseMemberUseCase;
    private final UpdateScreenFranchiseMemberOrderUseCase updateScreenFranchiseMemberOrderUseCase;
    private final RemoveScreenFranchiseMemberUseCase removeScreenFranchiseMemberUseCase;

    public AdminScreenFranchiseCommandController(
            CreateScreenFranchiseUseCase createScreenFranchiseUseCase,
            UpdateScreenFranchiseUseCase updateScreenFranchiseUseCase,
            AddScreenFranchiseMemberUseCase addScreenFranchiseMemberUseCase,
            UpdateScreenFranchiseMemberOrderUseCase updateScreenFranchiseMemberOrderUseCase,
            RemoveScreenFranchiseMemberUseCase removeScreenFranchiseMemberUseCase
    ) {
        this.createScreenFranchiseUseCase = Objects.requireNonNull(createScreenFranchiseUseCase, "CreateScreenFranchiseUseCase cannot be null.");
        this.updateScreenFranchiseUseCase = Objects.requireNonNull(updateScreenFranchiseUseCase, "UpdateScreenFranchiseUseCase cannot be null.");
        this.addScreenFranchiseMemberUseCase = Objects.requireNonNull(addScreenFranchiseMemberUseCase, "AddScreenFranchiseMemberUseCase cannot be null.");
        this.updateScreenFranchiseMemberOrderUseCase = Objects.requireNonNull(updateScreenFranchiseMemberOrderUseCase, "UpdateScreenFranchiseMemberOrderUseCase cannot be null.");
        this.removeScreenFranchiseMemberUseCase = Objects.requireNonNull(removeScreenFranchiseMemberUseCase, "RemoveScreenFranchiseMemberUseCase cannot be null.");
    }

    @PostMapping
    public String createFranchise(
            @ModelAttribute("form") CreateScreenFranchiseForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            validateFranchiseForm(form != null ? form.getName() : null, form != null ? form.getSlug() : null);

            ScreenFranchiseDTO created = createScreenFranchiseUseCase.execute(
                    new CreateScreenFranchiseCommand(form.getName(), form.getSlug())
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo franchise \"" + created.name() + "\".");
            return "redirect:/admin/screen/franchises/" + created.id() + "/memberships";

        } catch (ScreenFranchiseSlugAlreadyExistsException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/franchises/new";
        }
    }

    @PostMapping("/{franchiseId}/update")
    public String updateFranchise(
            @PathVariable UUID franchiseId,
            @ModelAttribute("form") EditScreenFranchiseForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            validateFranchiseForm(form != null ? form.getName() : null, form != null ? form.getSlug() : null);

            ScreenFranchiseDTO updated = updateScreenFranchiseUseCase.execute(
                    new UpdateScreenFranchiseCommand(franchiseId, form.getName(), form.getSlug())
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật franchise \"" + updated.name() + "\".");
            return "redirect:/admin/screen/franchises/" + franchiseId + "/memberships";

        } catch (ScreenFranchiseSlugAlreadyExistsException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/franchises/" + franchiseId + "/edit";
        } catch (ScreenFranchiseNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/franchises";
        }
    }

    @PostMapping("/{franchiseId}/memberships")
    public String addMember(
            @PathVariable UUID franchiseId,
            @ModelAttribute("form") AddScreenFranchiseMemberForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (form == null || form.getTitleId() == null) {
                throw new IllegalArgumentException("Vui lòng chọn tiêu đề Screen để thêm vào franchise.");
            }
            if (form.getDisplayOrder() != null && form.getDisplayOrder() < 0) {
                throw new IllegalArgumentException("Thứ tự hiển thị không được âm: " + form.getDisplayOrder());
            }

            ScreenFranchiseMemberDTO member = addScreenFranchiseMemberUseCase.execute(
                    new AddScreenFranchiseMemberCommand(franchiseId, form.getTitleId(), form.getDisplayOrder())
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã thêm tiêu đề \"" + member.title() + "\" vào franchise.");

        } catch (DuplicateScreenFranchiseMembershipException
                 | ScreenTitleNotFoundException
                 | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (ScreenFranchiseNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/franchises";
        }

        return "redirect:/admin/screen/franchises/" + franchiseId + "/memberships";
    }

    @PostMapping("/{franchiseId}/memberships/{membershipId}/order")
    public String updateMemberOrder(
            @PathVariable UUID franchiseId,
            @PathVariable UUID membershipId,
            @RequestParam("displayOrder") int displayOrder,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (displayOrder < 0) {
                throw new IllegalArgumentException("Thứ tự hiển thị không được âm: " + displayOrder);
            }

            updateScreenFranchiseMemberOrderUseCase.execute(
                    new UpdateScreenFranchiseMemberOrderCommand(franchiseId, membershipId, displayOrder)
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật thứ tự hiển thị thành " + displayOrder + ".");

        } catch (ScreenFranchiseMembershipNotFoundException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (ScreenFranchiseNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/franchises";
        }

        return "redirect:/admin/screen/franchises/" + franchiseId + "/memberships";
    }

    @PostMapping("/{franchiseId}/memberships/{membershipId}/remove")
    public String removeMember(
            @PathVariable UUID franchiseId,
            @PathVariable UUID membershipId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            removeScreenFranchiseMemberUseCase.execute(
                    new RemoveScreenFranchiseMemberCommand(franchiseId, membershipId)
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã xóa tiêu đề khỏi franchise.");

        } catch (ScreenFranchiseMembershipNotFoundException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (ScreenFranchiseNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/franchises";
        }

        return "redirect:/admin/screen/franchises/" + franchiseId + "/memberships";
    }

    private void validateFranchiseForm(String name, String slug) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên franchise không được để trống.");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Slug franchise không được để trống.");
        }
    }
}
