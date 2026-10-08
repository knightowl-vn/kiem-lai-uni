package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleListUseCase;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.entry.admin.form.CreateScreenTitleForm;
import com.universe.screen.entry.admin.form.EditScreenTitleForm;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/screen/titles")
public class AdminScreenTitlePageController {

    private static final String ACTIVE_MENU = "screen";
    private static final String ACTIVE_SUB_MENU = "titles";

    private final GetScreenTitleListUseCase getScreenTitleListUseCase;
    private final GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    public AdminScreenTitlePageController(
            GetScreenTitleListUseCase getScreenTitleListUseCase,
            GetScreenTitleDetailUseCase getScreenTitleDetailUseCase
    ) {
        this.getScreenTitleListUseCase = Objects.requireNonNull(getScreenTitleListUseCase, "GetScreenTitleListUseCase cannot be null.");
        this.getScreenTitleDetailUseCase = Objects.requireNonNull(getScreenTitleDetailUseCase, "GetScreenTitleDetailUseCase cannot be null.");
    }

    @GetMapping({"", "/"})
    public String listPage(Model model, HttpServletResponse response) {
        disableCaching(response);

        List<ScreenTitleDTO> titles = getScreenTitleListUseCase.execute();

        model.addAttribute("titles", titles);
        model.addAttribute("pageTitle", "Quản lý Screen Title");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/titles";
    }

    @GetMapping("/new")
    public String createPage(Model model, HttpServletResponse response) {
        disableCaching(response);

        model.addAttribute("form", new CreateScreenTitleForm());
        model.addAttribute("structures", ScreenStructure.values());
        model.addAttribute("presentations", ScreenPresentation.values());
        model.addAttribute("pageTitle", "Tạo Screen Title");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/title-create";
    }

    @GetMapping("/{titleId}")
    public String detailPage(@PathVariable UUID titleId, Model model, HttpServletResponse response) {
        disableCaching(response);

        ScreenTitleDTO title = getScreenTitleDetailUseCase.execute(titleId);

        model.addAttribute("title", title);
        model.addAttribute("pageTitle", "Chi tiết Screen Title");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/title-detail";
    }

    @GetMapping("/{titleId}/edit")
    public String editPage(@PathVariable UUID titleId, Model model, HttpServletResponse response) {
        disableCaching(response);

        ScreenTitleDTO title = getScreenTitleDetailUseCase.execute(titleId);

        EditScreenTitleForm form = new EditScreenTitleForm(
                title.title(),
                title.slug(),
                title.originalTitle(),
                title.synopsis(),
                title.structure(),
                title.presentation(),
                title.posterMediaAssetId() != null ? title.posterMediaAssetId().toString() : null,
                title.backdropMediaAssetId() != null ? title.backdropMediaAssetId().toString() : null
        );

        model.addAttribute("title", title);
        model.addAttribute("form", form);
        model.addAttribute("structures", ScreenStructure.values());
        model.addAttribute("presentations", ScreenPresentation.values());
        model.addAttribute("pageTitle", "Chỉnh sửa Screen Title");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/title-edit";
    }

    private void disableCaching(HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
    }
}
