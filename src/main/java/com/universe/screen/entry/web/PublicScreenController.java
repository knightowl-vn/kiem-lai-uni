package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;

/**
 * Public Screen catalog controller rendering the published audiovisual catalog at {@code /screen}.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Guest-readable and authenticated-readable;</li>
 *   <li>Exposes only published Screen titles;</li>
 *   <li>Uses public application query capability;</li>
 *   <li>Server-rendered Thymeleaf view;</li>
 *   <li>Read-only; zero mutation.</li>
 * </ul>
 */
@Controller
@RequestMapping("/screen")
public class PublicScreenController {

    private final GetPublicScreenCatalogUseCase getPublicScreenCatalogUseCase;

    public PublicScreenController(GetPublicScreenCatalogUseCase getPublicScreenCatalogUseCase) {
        this.getPublicScreenCatalogUseCase = Objects.requireNonNull(
                getPublicScreenCatalogUseCase,
                "GetPublicScreenCatalogUseCase cannot be null."
        );
    }

    @GetMapping({"", "/"})
    public String catalogPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + GetPublicScreenCatalogUseCase.DEFAULT_PAGE_SIZE) int size,
            Model model
    ) {
        PublicScreenCatalogPageDTO catalogPageDTO = getPublicScreenCatalogUseCase.execute(page, size);
        PublicScreenCatalogPageView catalogPage = PublicScreenCatalogPageView.fromDTO(catalogPageDTO);

        model.addAttribute("catalogPage", catalogPage);
        model.addAttribute("activeNav", "screen");
        model.addAttribute("pageTitle", "Phim - Kiếm Lai Universe");

        return "screen/catalog";
    }
}
