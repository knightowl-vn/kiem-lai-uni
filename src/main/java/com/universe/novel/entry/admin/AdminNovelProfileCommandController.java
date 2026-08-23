package com.universe.novel.entry.admin;

import com.universe.novel.application.profile.GetNovelProfileUseCase;
import com.universe.novel.application.profile.NovelCoverUpload;
import com.universe.novel.application.profile.UpdateNovelProfileCommand;
import com.universe.novel.application.profile.UpdateNovelProfileUseCase;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.domain.NovelStatus;
import com.universe.novel.entry.admin.form.EditNovelProfileForm;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.Objects;

@Controller
@RequestMapping("/admin/novel")
public class AdminNovelProfileCommandController {

    private final GetNovelProfileUseCase
            getNovelProfileUseCase;

    private final UpdateNovelProfileUseCase
            updateNovelProfileUseCase;

    public AdminNovelProfileCommandController(
            GetNovelProfileUseCase getNovelProfileUseCase,
            UpdateNovelProfileUseCase updateNovelProfileUseCase
    ) {
        this.getNovelProfileUseCase =
                Objects.requireNonNull(
                        getNovelProfileUseCase,
                        "GetNovelProfileUseCase không được để trống."
                );

        this.updateNovelProfileUseCase =
                Objects.requireNonNull(
                        updateNovelProfileUseCase,
                        "UpdateNovelProfileUseCase không được để trống."
                );
    }

    @PostMapping("/profile")
    public String updateProfile(
            @ModelAttribute("form") EditNovelProfileForm form,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            NovelCoverUpload coverUpload = null;
            MultipartFile coverFile = form.getCoverImageFile();

            if (coverFile != null && !coverFile.isEmpty()) {
                coverUpload = new NovelCoverUpload(
                        coverFile.getOriginalFilename(),
                        coverFile.getContentType(),
                        coverFile.getBytes()
                );
            }

            UpdateNovelProfileCommand command =
                    new UpdateNovelProfileCommand(
                            form.getTitle(),
                            form.getAuthor(),
                            form.getDescription(),
                            form.getStatus(),
                            coverUpload
                    );

            updateNovelProfileUseCase.execute(
                    command
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Cập nhật hồ sơ Novel thành công."
            );

            return "redirect:/admin/novel/profile";

        } catch (IllegalArgumentException | IllegalStateException | IOException ex) {
            NovelProfileDTO profile =
                    getNovelProfileUseCase.execute();

            model.addAttribute(
                    "profile",
                    profile
            );

            model.addAttribute(
                    "form",
                    form
            );

            model.addAttribute(
                    "statuses",
                    NovelStatus.values()
            );

            model.addAttribute(
                    "pageTitle",
                    "Hồ sơ Novel"
            );

            model.addAttribute(
                    "activeMenu",
                    "novel"
            );

            model.addAttribute(
                    "activeSubMenu",
                    "profile"
            );

            model.addAttribute(
                    "errorMessage",
                    ex.getMessage()
            );

            return "admin/novel/profile";
        }
    }
}
