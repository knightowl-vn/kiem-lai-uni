package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenEntryListUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.entry.admin.form.CreateScreenEntryForm;
import com.universe.screen.entry.admin.form.EditScreenEntryForm;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/titles/{titleId}/entries")
public class AdminScreenEntryPageController {

    private static final String ACTIVE_MENU = "screen";
    private static final String ACTIVE_SUB_MENU = "titles";

    private final GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;
    private final GetScreenEntryListUseCase getScreenEntryListUseCase;
    private final GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    public AdminScreenEntryPageController(
            GetScreenTitleDetailUseCase getScreenTitleDetailUseCase,
            GetScreenEntryListUseCase getScreenEntryListUseCase,
            GetScreenEntryDetailUseCase getScreenEntryDetailUseCase
    ) {
        this.getScreenTitleDetailUseCase = Objects.requireNonNull(getScreenTitleDetailUseCase, "GetScreenTitleDetailUseCase cannot be null.");
        this.getScreenEntryListUseCase = Objects.requireNonNull(getScreenEntryListUseCase, "GetScreenEntryListUseCase cannot be null.");
        this.getScreenEntryDetailUseCase = Objects.requireNonNull(getScreenEntryDetailUseCase, "GetScreenEntryDetailUseCase cannot be null.");
    }

    @GetMapping({"", "/"})
    public String listPage(
            @PathVariable UUID titleId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        disableCaching(response);

        try {
            ScreenTitleDTO title = getScreenTitleDetailUseCase.execute(titleId);
            List<ScreenEntryDTO> entries = getScreenEntryListUseCase.execute(titleId);

            model.addAttribute("title", title);
            model.addAttribute("entries", entries);
            model.addAttribute("pageTitle", "Quản lý Screen Entry - " + title.title());
            model.addAttribute("activeMenu", ACTIVE_MENU);
            model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

            return "admin/screen/entries";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    public String listPage(UUID titleId, Model model, HttpServletResponse response) {
        return listPage(titleId, model, response, new RedirectAttributesModelMap());
    }

    @GetMapping("/new")
    public String createPage(
            @PathVariable UUID titleId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        disableCaching(response);

        try {
            ScreenTitleDTO title = getScreenTitleDetailUseCase.execute(titleId);
            List<ScreenEntryType> compatibleTypes = Arrays.stream(ScreenEntryType.values())
                    .filter(t -> t.isCompatibleWith(title.structure()))
                    .toList();

            model.addAttribute("title", title);
            model.addAttribute("form", new CreateScreenEntryForm());
            model.addAttribute("types", compatibleTypes);
            model.addAttribute("pageTitle", "Tạo Screen Entry - " + title.title());
            model.addAttribute("activeMenu", ACTIVE_MENU);
            model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

            return "admin/screen/entry-create";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        }
    }

    public String createPage(UUID titleId, Model model, HttpServletResponse response) {
        return createPage(titleId, model, response, new RedirectAttributesModelMap());
    }

    @GetMapping("/{entryId}")
    public String detailPage(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        disableCaching(response);

        try {
            ScreenTitleDTO title = getScreenTitleDetailUseCase.execute(titleId);
            ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

            model.addAttribute("title", title);
            model.addAttribute("entry", entry);
            model.addAttribute("pageTitle", "Chi tiết Screen Entry - " + entry.title());
            model.addAttribute("activeMenu", ACTIVE_MENU);
            model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

            return "admin/screen/entry-detail";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        }
    }

    public String detailPage(UUID titleId, UUID entryId, Model model, HttpServletResponse response) {
        return detailPage(titleId, entryId, model, response, new RedirectAttributesModelMap());
    }

    @GetMapping("/{entryId}/edit")
    public String editPage(
            @PathVariable UUID titleId,
            @PathVariable UUID entryId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        disableCaching(response);

        try {
            ScreenTitleDTO title = getScreenTitleDetailUseCase.execute(titleId);
            ScreenEntryDTO entry = getScreenEntryDetailUseCase.execute(titleId, entryId);

            EditScreenEntryForm form = new EditScreenEntryForm(
                    entry.title(),
                    entry.slug(),
                    entry.type(),
                    entry.number(),
                    entry.synopsis(),
                    entry.durationSeconds(),
                    entry.videoMediaAssetId() != null ? entry.videoMediaAssetId().toString() : null,
                    entry.thumbnailMediaAssetId() != null ? entry.thumbnailMediaAssetId().toString() : null
            );

            List<ScreenEntryType> compatibleTypes = Arrays.stream(ScreenEntryType.values())
                    .filter(t -> t.isCompatibleWith(title.structure()))
                    .toList();

            model.addAttribute("title", title);
            model.addAttribute("entry", entry);
            model.addAttribute("form", form);
            model.addAttribute("types", compatibleTypes);
            model.addAttribute("pageTitle", "Chỉnh sửa Screen Entry - " + entry.title());
            model.addAttribute("activeMenu", ACTIVE_MENU);
            model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

            return "admin/screen/entry-edit";
        } catch (ScreenTitleNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles";
        } catch (ScreenEntryNotFoundException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/screen/titles/" + titleId + "/entries";
        }
    }

    public String editPage(UUID titleId, UUID entryId, Model model, HttpServletResponse response) {
        return editPage(titleId, entryId, model, response, new RedirectAttributesModelMap());
    }

    private void disableCaching(HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
    }
}
