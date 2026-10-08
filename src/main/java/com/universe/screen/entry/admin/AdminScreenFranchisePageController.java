package com.universe.screen.entry.admin;

import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.dto.ScreenTitleSummaryDTO;
import com.universe.screen.application.usecase.GetAvailableScreenTitlesUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseDetailUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseMembersUseCase;
import com.universe.screen.entry.admin.form.AddScreenFranchiseMemberForm;
import com.universe.screen.entry.admin.form.CreateScreenFranchiseForm;
import com.universe.screen.entry.admin.form.EditScreenFranchiseForm;
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
@RequestMapping("/admin/screen/franchises")
public class AdminScreenFranchisePageController {

    private static final String ACTIVE_MENU = "screen";
    private static final String ACTIVE_SUB_MENU = "franchises";

    private final GetScreenFranchiseListUseCase getScreenFranchiseListUseCase;
    private final GetScreenFranchiseDetailUseCase getScreenFranchiseDetailUseCase;
    private final GetScreenFranchiseMembersUseCase getScreenFranchiseMembersUseCase;
    private final GetAvailableScreenTitlesUseCase getAvailableScreenTitlesUseCase;

    public AdminScreenFranchisePageController(
            GetScreenFranchiseListUseCase getScreenFranchiseListUseCase,
            GetScreenFranchiseDetailUseCase getScreenFranchiseDetailUseCase,
            GetScreenFranchiseMembersUseCase getScreenFranchiseMembersUseCase,
            GetAvailableScreenTitlesUseCase getAvailableScreenTitlesUseCase
    ) {
        this.getScreenFranchiseListUseCase = Objects.requireNonNull(getScreenFranchiseListUseCase, "GetScreenFranchiseListUseCase cannot be null.");
        this.getScreenFranchiseDetailUseCase = Objects.requireNonNull(getScreenFranchiseDetailUseCase, "GetScreenFranchiseDetailUseCase cannot be null.");
        this.getScreenFranchiseMembersUseCase = Objects.requireNonNull(getScreenFranchiseMembersUseCase, "GetScreenFranchiseMembersUseCase cannot be null.");
        this.getAvailableScreenTitlesUseCase = Objects.requireNonNull(getAvailableScreenTitlesUseCase, "GetAvailableScreenTitlesUseCase cannot be null.");
    }

    @GetMapping({"", "/"})
    public String listPage(Model model, HttpServletResponse response) {
        disableCaching(response);

        List<ScreenFranchiseDTO> franchises = getScreenFranchiseListUseCase.execute();

        model.addAttribute("franchises", franchises);
        model.addAttribute("pageTitle", "Quản lý Screen Franchise");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/franchises";
    }

    @GetMapping("/new")
    public String createPage(Model model, HttpServletResponse response) {
        disableCaching(response);

        model.addAttribute("form", new CreateScreenFranchiseForm());
        model.addAttribute("pageTitle", "Tạo Franchise");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/franchise-create";
    }

    @GetMapping("/{franchiseId}")
    public String detailRedirect(@PathVariable UUID franchiseId) {
        return "redirect:/admin/screen/franchises/" + franchiseId + "/memberships";
    }

    @GetMapping("/{franchiseId}/edit")
    public String editPage(@PathVariable UUID franchiseId, Model model, HttpServletResponse response) {
        disableCaching(response);

        ScreenFranchiseDTO franchise = getScreenFranchiseDetailUseCase.execute(franchiseId);

        model.addAttribute("franchise", franchise);
        model.addAttribute("form", new EditScreenFranchiseForm(franchise.name(), franchise.slug()));
        model.addAttribute("pageTitle", "Chỉnh sửa Franchise");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/franchise-edit";
    }

    @GetMapping("/{franchiseId}/memberships")
    public String membershipsPage(@PathVariable UUID franchiseId, Model model, HttpServletResponse response) {
        disableCaching(response);

        ScreenFranchiseDTO franchise = getScreenFranchiseDetailUseCase.execute(franchiseId);
        List<ScreenFranchiseMemberDTO> members = getScreenFranchiseMembersUseCase.execute(franchiseId);
        List<ScreenTitleSummaryDTO> availableTitles = getAvailableScreenTitlesUseCase.execute(franchiseId);

        model.addAttribute("franchise", franchise);
        model.addAttribute("members", members);
        model.addAttribute("availableTitles", availableTitles);
        model.addAttribute("form", new AddScreenFranchiseMemberForm());
        model.addAttribute("pageTitle", "Quản lý thành viên Franchise");
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("activeSubMenu", ACTIVE_SUB_MENU);

        return "admin/screen/franchise-memberships";
    }

    private void disableCaching(HttpServletResponse response) {
        if (response != null) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
    }
}
