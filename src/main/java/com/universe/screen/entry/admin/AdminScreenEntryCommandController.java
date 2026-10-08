package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.CreateScreenEntryUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.entry.admin.form.CreateScreenEntryForm;
import com.universe.screen.entry.admin.form.EditScreenEntryForm;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/titles/{titleId}/entries")
public class AdminScreenEntryCommandController {

    private final CreateScreenEntryUseCase createScreenEntryUseCase;
    private final UpdateScreenEntryUseCase updateScreenEntryUseCase;
    private final PublishScreenEntryUseCase publishScreenEntryUseCase;
    private final ArchiveScreenEntryUseCase archiveScreenEntryUseCase;

    public AdminScreenEntryCommandController(
            CreateScreenEntryUseCase createScreenEntryUseCase,
            UpdateScreenEntryUseCase updateScreenEntryUseCase,
            PublishScreenEntryUseCase publishScreenEntryUseCase,
            ArchiveScreenEntryUseCase archiveScreenEntryUseCase
    ) {
        this.createScreenEntryUseCase = Objects.requireNonNull(createScreenEntryUseCase, "CreateScreenEntryUseCase cannot be null.");
        this.updateScreenEntryUseCase = Objects.requireNonNull(updateScreenEntryUseCase, "UpdateScreenEntryUseCase cannot be null.");
        this.publishScreenEntryUseCase = Objects.requireNonNull(publishScreenEntryUseCase, "PublishScreenEntryUseCase cannot be null.");
        this.archiveScreenEntryUseCase = Objects.requireNonNull(archiveScreenEntryUseCase, "ArchiveScreenEntryUseCase cannot be null.");
    }

    @PostMapping
    public String createEntry(
            @PathVariable UUID titleId,
            @ModelAttribute("form") CreateScreenEntryForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", resolveBindingErrorMessage(bindingResult));
            return "redirect:/admin/screen/titles/" + titleId + "/entries/new";
        }

        try {
            if (form == null) {
                throw new IllegalArgumentException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateEntryForm(form.getTitle(), form.getSlug(), form.getType());

            ScreenEntryDTO created = createScreenEntryUseCase.execute(
                    new CreateScreenEntryCommand(
                            titleId,
                            form.getSlug(),
                            form.getType(),
                            form.getNumber(),
                            form.getTitle(),
                            form.getSynopsis(),
                            form.getDurationSeconds()
                    )
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo Screen Entry \"" + created.title() + "\".");
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + created.id();

        } catch (ScreenEntrySlugAlreadyExistsException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries/new";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    @PostMapping("/{entryId}/update")
    public String updateEntry(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            @ModelAttribute("form") EditScreenEntryForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            redirectAttributes.addFlashAttribute("errorMessage", resolveBindingErrorMessage(bindingResult));
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit";
        }

        try {
            if (form == null) {
                throw new IllegalArgumentException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateEntryForm(form.getTitle(), form.getSlug(), form.getType());

            ScreenEntryDTO updated = updateScreenEntryUseCase.execute(
                    new UpdateScreenEntryCommand(
                            titleId,
                            entryId,
                            form.getSlug(),
                            form.getType(),
                            form.getNumber(),
                            form.getTitle(),
                            form.getSynopsis(),
                            form.getDurationSeconds()
                    )
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật Screen Entry \"" + updated.title() + "\".");
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;

        } catch (ScreenEntrySlugAlreadyExistsException
                 | IllegalArgumentException
                 | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId + "/edit";
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    @PostMapping("/{entryId}/publish")
    public String publishEntry(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenEntryDTO published = publishScreenEntryUseCase.execute(titleId, entryId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xuất bản Screen Entry \"" + published.title() + "\".");
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;
    }

    @PostMapping("/{entryId}/archive")
    public String archiveEntry(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenEntryDTO archived = archiveScreenEntryUseCase.execute(titleId, entryId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trữ Screen Entry \"" + archived.title() + "\".");
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId + "/entries/" + entryId;
    }

    private void validateEntryForm(String title, String slug, com.universe.screen.domain.ScreenEntryType type) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề Screen entry không được để trống.");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Slug URL không được để trống.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Vui lòng chọn loại nội dung (Type: EPISODE, FEATURE, SPECIAL, TRAILER).");
        }
    }

    private String resolveBindingErrorMessage(BindingResult bindingResult) {
        if (bindingResult.hasFieldErrors("type")) {
            return "Loại tập phim/video không hợp lệ.";
        }
        if (bindingResult.hasFieldErrors("number")) {
            return "Số tập/thứ tự không hợp lệ.";
        }
        if (bindingResult.hasFieldErrors("durationSeconds")) {
            return "Thời lượng không hợp lệ.";
        }
        var error = bindingResult.getAllErrors().stream().findFirst();
        if (error.isPresent() && error.get().getDefaultMessage() != null && !error.get().getDefaultMessage().isBlank()) {
            return error.get().getDefaultMessage();
        }
        return "Dữ liệu biểu mẫu không hợp lệ.";
    }
}
