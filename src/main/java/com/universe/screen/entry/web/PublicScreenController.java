package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.dto.PublicScreenEntryDetailDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.application.dto.PublicScreenWatchDTO;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import com.universe.screen.application.usecase.GetPublicScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenWatchUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Objects;

/**
 * Public Screen controller rendering the published audiovisual catalog at {@code /screen}
 * and published title detail at {@code /screen/{titleSlug}}.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Guest-readable and authenticated-readable;</li>
 *   <li>Exposes only published Screen titles and entries;</li>
 *   <li>Uses public application query capabilities;</li>
 *   <li>Server-rendered Thymeleaf views;</li>
 *   <li>Read-only; zero mutation.</li>
 * </ul>
 */
@Controller
@RequestMapping("/screen")
public class PublicScreenController {

    private final GetPublicScreenCatalogUseCase getPublicScreenCatalogUseCase;
    private final GetPublicScreenTitleDetailUseCase getPublicScreenTitleDetailUseCase;
    private final GetPublicScreenEntryDetailUseCase getPublicScreenEntryDetailUseCase;
    private final GetPublicScreenWatchUseCase getPublicScreenWatchUseCase;

    public PublicScreenController(
            GetPublicScreenCatalogUseCase getPublicScreenCatalogUseCase,
            GetPublicScreenTitleDetailUseCase getPublicScreenTitleDetailUseCase,
            GetPublicScreenEntryDetailUseCase getPublicScreenEntryDetailUseCase,
            GetPublicScreenWatchUseCase getPublicScreenWatchUseCase
    ) {
        this.getPublicScreenCatalogUseCase = Objects.requireNonNull(
                getPublicScreenCatalogUseCase,
                "GetPublicScreenCatalogUseCase cannot be null."
        );
        this.getPublicScreenTitleDetailUseCase = Objects.requireNonNull(
                getPublicScreenTitleDetailUseCase,
                "GetPublicScreenTitleDetailUseCase cannot be null."
        );
        this.getPublicScreenEntryDetailUseCase = Objects.requireNonNull(
                getPublicScreenEntryDetailUseCase,
                "GetPublicScreenEntryDetailUseCase cannot be null."
        );
        this.getPublicScreenWatchUseCase = Objects.requireNonNull(
                getPublicScreenWatchUseCase,
                "GetPublicScreenWatchUseCase cannot be null."
        );
    }

    @GetMapping({"", "/"})
    public String catalogPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + GetPublicScreenCatalogUseCase.DEFAULT_PAGE_SIZE) int size,
            HttpServletRequest request,
            Model model
    ) {
        PublicScreenCatalogPageDTO catalogPageDTO = getPublicScreenCatalogUseCase.execute(page, size);
        PublicScreenCatalogPageView catalogPage = PublicScreenCatalogPageView.fromDTO(catalogPageDTO);

        model.addAttribute("catalogPage", catalogPage);
        model.addAttribute("activeNav", "screen");
        model.addAttribute("pageTitle", "Phim - Kiếm Lai Universe");
        exposeCsrfToken(request, model);

        return "screen/catalog";
    }

    @GetMapping("/{titleSlug}")
    public String detailPage(
            @PathVariable String titleSlug,
            HttpServletRequest request,
            Model model
    ) {
        PublicScreenTitleDetailDTO detailDTO = getPublicScreenTitleDetailUseCase.execute(titleSlug);
        PublicScreenTitleDetailView titleDetail = PublicScreenTitleDetailView.fromDTO(detailDTO);

        model.addAttribute("titleDetail", titleDetail);
        model.addAttribute("activeNav", "screen");
        model.addAttribute("pageTitle", titleDetail.title() + " - Kiếm Lai Universe");
        exposeCsrfToken(request, model);

        return "screen/detail";
    }

    /**
     * Backward-compatible entry route redirecting to canonical watch destination:
     * {@code /screen/{titleSlug}/watch/{entrySlug}}.
     *
     * <p>Enforces published-only and entry-ownership validation to prevent data leakage,
     * while preserving query parameters (e.g. comment/thread deep links) and discussion fragment.
     */
    @GetMapping("/{titleSlug}/entries/{entrySlug}")
    public String entryDetailPage(
            @PathVariable String titleSlug,
            @PathVariable String entrySlug,
            HttpServletRequest request
    ) {
        // Enforce published-only and title/entry ownership verification to preserve privacy contract
        getPublicScreenWatchUseCase.execute(titleSlug, entrySlug);

        // Build canonical redirect URL with query params and discussion anchor preserved
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/screen/{titleSlug}/watch/{entrySlug}");
        if (request != null && request.getParameterMap() != null) {
            request.getParameterMap().forEach((key, values) -> {
                if (values != null) {
                    for (String val : values) {
                        builder.queryParam(key, val);
                    }
                }
            });
        }
        if (request != null && (request.getParameter("commentId") != null || request.getParameter("threadId") != null)) {
            builder.fragment("screenDiscussion");
        }

        String destination = builder.buildAndExpand(titleSlug, entrySlug).toUriString();
        return "redirect:" + destination;
    }

    @GetMapping("/{titleSlug}/watch/{entrySlug}")
    public String watchPage(
            @PathVariable String titleSlug,
            @PathVariable String entrySlug,
            HttpServletRequest request,
            Model model
    ) {
        PublicScreenWatchDTO watchDTO = getPublicScreenWatchUseCase.execute(titleSlug, entrySlug);
        PublicScreenWatchView watchView = PublicScreenWatchView.fromDTO(watchDTO);

        model.addAttribute("watchView", watchView);
        model.addAttribute("activeNav", "screen");
        model.addAttribute("pageTitle", watchView.pageTitle());
        exposeCsrfToken(request, model);

        return "screen/watch";
    }

    private void exposeCsrfToken(HttpServletRequest request, Model model) {
        if (model == null) {
            return;
        }
        CsrfToken csrfToken = resolveCsrfToken(request);
        if (csrfToken != null) {
            String token = csrfToken.getToken();
            String header = csrfToken.getHeaderName();
            model.addAttribute("csrfToken", token != null ? token : "");
            model.addAttribute("csrfHeader", header != null ? header : "X-CSRF-TOKEN");
        } else {
            model.addAttribute("csrfToken", "");
            model.addAttribute("csrfHeader", "X-CSRF-TOKEN");
        }
    }

    private CsrfToken resolveCsrfToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object attr = request.getAttribute(CsrfToken.class.getName());
        if (attr instanceof CsrfToken token) {
            return token;
        }
        Object fallback = request.getAttribute("_csrf");
        if (fallback instanceof CsrfToken token) {
            return token;
        }
        return null;
    }
}
