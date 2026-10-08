package com.universe.screen.entry.admin;

import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleSlugAlreadyExistsException;
import com.universe.screen.application.usecase.ArchiveScreenTitleUseCase;
import com.universe.screen.application.usecase.CreateScreenTitleUseCase;
import com.universe.screen.application.usecase.PublishScreenTitleUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.entry.admin.form.CreateScreenTitleForm;
import com.universe.screen.entry.admin.form.EditScreenTitleForm;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/titles")
public class AdminScreenTitleCommandController {

    private final CreateScreenTitleUseCase createScreenTitleUseCase;
    private final UpdateScreenTitleUseCase updateScreenTitleUseCase;
    private final PublishScreenTitleUseCase publishScreenTitleUseCase;
    private final ArchiveScreenTitleUseCase archiveScreenTitleUseCase;

    public AdminScreenTitleCommandController(
            CreateScreenTitleUseCase createScreenTitleUseCase,
            UpdateScreenTitleUseCase updateScreenTitleUseCase,
            PublishScreenTitleUseCase publishScreenTitleUseCase,
            ArchiveScreenTitleUseCase archiveScreenTitleUseCase
    ) {
        this.createScreenTitleUseCase = Objects.requireNonNull(createScreenTitleUseCase, "CreateScreenTitleUseCase cannot be null.");
        this.updateScreenTitleUseCase = Objects.requireNonNull(updateScreenTitleUseCase, "UpdateScreenTitleUseCase cannot be null.");
        this.publishScreenTitleUseCase = Objects.requireNonNull(publishScreenTitleUseCase, "PublishScreenTitleUseCase cannot be null.");
        this.archiveScreenTitleUseCase = Objects.requireNonNull(archiveScreenTitleUseCase, "ArchiveScreenTitleUseCase cannot be null.");
    }

    @PostMapping
    public String createTitle(
            @ModelAttribute("form") CreateScreenTitleForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (form == null) {
                throw new IllegalArgumentException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateTitleForm(form.getTitle(), form.getSlug(), form.getStructure(), form.getPresentation());

            UUID posterId = parseOptionalUuid(form.getPosterMediaAssetId(), "poster");
            UUID backdropId = parseOptionalUuid(form.getBackdropMediaAssetId(), "backdrop");

            ScreenTitleDTO created = createScreenTitleUseCase.execute(
                    new CreateScreenTitleCommand(
                            form.getSlug(),
                            form.getTitle(),
                            form.getOriginalTitle(),
                            form.getSynopsis(),
                            form.getStructure(),
                            form.getPresentation(),
                            posterId,
                            backdropId
                    )
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã tạo Screen Title \"" + created.title() + "\".");
            return "redirect:/admin/screen/titles/" + created.id();

        } catch (ScreenTitleSlugAlreadyExistsException
                 | ScreenMediaAssetNotFoundException
                 | InvalidScreenMediaAssetTypeException
                 | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/new";
        }
    }

    @PostMapping("/{titleId}/update")
    public String updateTitle(
            @PathVariable UUID titleId,
            @ModelAttribute("form") EditScreenTitleForm form,
            RedirectAttributes redirectAttributes
    ) {
        try {
            if (form == null) {
                throw new IllegalArgumentException("Dữ liệu biểu mẫu không hợp lệ.");
            }
            validateTitleForm(form.getTitle(), form.getSlug(), form.getStructure(), form.getPresentation());

            UUID posterId = parseOptionalUuid(form.getPosterMediaAssetId(), "poster");
            UUID backdropId = parseOptionalUuid(form.getBackdropMediaAssetId(), "backdrop");

            ScreenTitleDTO updated = updateScreenTitleUseCase.execute(
                    new UpdateScreenTitleCommand(
                            titleId,
                            form.getSlug(),
                            form.getTitle(),
                            form.getOriginalTitle(),
                            form.getSynopsis(),
                            form.getStructure(),
                            form.getPresentation(),
                            posterId,
                            backdropId
                    )
            );

            redirectAttributes.addFlashAttribute("successMessage", "Đã cập nhật Screen Title \"" + updated.title() + "\".");
            return "redirect:/admin/screen/titles/" + titleId;

        } catch (ScreenTitleSlugAlreadyExistsException
                 | ScreenMediaAssetNotFoundException
                 | InvalidScreenMediaAssetTypeException
                 | IllegalArgumentException
                 | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/edit";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    @PostMapping("/{titleId}/publish")
    public String publishTitle(
            @PathVariable UUID titleId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenTitleDTO published = publishScreenTitleUseCase.execute(titleId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã xuất bản Screen Title \"" + published.title() + "\".");
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId;
    }

    @PostMapping("/{titleId}/archive")
    public String archiveTitle(
            @PathVariable UUID titleId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            ScreenTitleDTO archived = archiveScreenTitleUseCase.execute(titleId);
            redirectAttributes.addFlashAttribute("successMessage", "Đã lưu trữ Screen Title \"" + archived.title() + "\".");
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/admin/screen/titles/" + titleId;
    }

    private UUID parseOptionalUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("ID media asset " + fieldName + " không đúng định dạng UUID: " + value.trim(), ex);
        }
    }

    private void validateTitleForm(String title, String slug, ScreenStructure structure, ScreenPresentation presentation) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề Screen không được để trống.");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Slug URL không được để trống.");
        }
        if (structure == null) {
            throw new IllegalArgumentException("Vui lòng chọn cấu trúc (Structure: SERIES hoặc FEATURE).");
        }
        if (presentation == null) {
            throw new IllegalArgumentException("Vui lòng chọn phong cách thể hiện (Presentation: ANIMATION_3D hoặc LIVE_ACTION).");
        }
    }
}
