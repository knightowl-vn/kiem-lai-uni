package com.universe.novel.entry.admin;

import com.universe.novel.application.profile.GetNovelProfileUseCase;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.novel.domain.NovelStatus;
import com.universe.novel.entry.admin.form.EditNovelProfileForm;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Objects;

@Controller
@RequestMapping("/admin/novel")
public class AdminNovelProfilePageController {

    private final GetNovelProfileUseCase
            getNovelProfileUseCase;

    public AdminNovelProfilePageController(
            GetNovelProfileUseCase getNovelProfileUseCase
    ) {
        this.getNovelProfileUseCase =
                Objects.requireNonNull(
                        getNovelProfileUseCase,
                        "GetNovelProfileUseCase không được để trống."
                );
    }

    @GetMapping("/profile")
    public String profilePage(
            Model model
    ) {
        NovelProfileDTO profile =
                getNovelProfileUseCase.execute();

        String displayCoverUrl = profile.displayCoverImageUrl();

        if (!model.containsAttribute("form")) {
            EditNovelProfileForm form =
                    new EditNovelProfileForm();
            form.setTitle(profile.title());
            form.setAuthor(profile.author());
            form.setDescription(profile.description());
            form.setCoverImageUrl(displayCoverUrl);
            form.setStatus(profile.status());

            model.addAttribute(
                    "form",
                    form
            );
        }

        model.addAttribute(
                "profile",
                profile
        );

        model.addAttribute(
                "displayCoverUrl",
                displayCoverUrl
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

        return "admin/novel/profile";
    }
}
