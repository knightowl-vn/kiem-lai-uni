package com.universe.wiki.entry.admin;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.contribution.query.WikiContributionAdminFilter;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.entry.admin.dto.AdminWikiContributionQueuePageDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative controller for rendering the read-only Wiki contribution inbox page.
 *
 * <p>Protected by Spring Security under {@code /admin/**}, requiring role {@code ADMIN}
 * or {@code SUPER_ADMIN}. Provides triage queue discovery, deterministic sorting, filtering,
 * and pagination without exposing any mutation actions.
 */
@Controller
@RequestMapping("/admin/wiki/contributions")
public class AdminWikiContributionPageController {

    private static final String VIEW_NAME = "admin/wiki/contributions";
    private static final String PAGE_TITLE = "Quản lý đóng góp Wiki";
    private static final String ACTIVE_MENU = "wiki-contributions";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final AdminWikiContributionCoordinator coordinator;

    public AdminWikiContributionPageController(AdminWikiContributionCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "AdminWikiContributionCoordinator cannot be null"
        );
    }

    @GetMapping({ "", "/" })
    public String inboxPage(
            @RequestParam(required = false) String status,
            @RequestParam(name = "type", required = false) String contributionType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model,
            HttpServletResponse response
    ) {
        if (response != null) {
            disableCaching(response);
        }

        int safePage = Math.max(0, page);
        int safeSize = size > 0 ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        String normalizedStatusStr = normalizeStatusString(status);
        WikiContributionStatus filterStatus = resolveFilterStatus(normalizedStatusStr);
        WikiContributionType filterType = resolveFilterType(contributionType);
        String normalizedKeyword = normalizeKeyword(keyword);

        WikiContributionAdminFilter filter = new WikiContributionAdminFilter(
                filterStatus,
                filterType,
                normalizedKeyword,
                safePage,
                safeSize
        );

        AdminWikiContributionQueuePageDTO queuePage = coordinator.getInboxPage(filter);
        long newCount = coordinator.getNewContributionCount();

        model.addAttribute("pageTitle", PAGE_TITLE);
        model.addAttribute("activeMenu", ACTIVE_MENU);
        model.addAttribute("contributionPage", queuePage);
        model.addAttribute("items", queuePage.items());
        model.addAttribute("newContributionCount", newCount);

        model.addAttribute("selectedStatus", normalizedStatusStr);
        model.addAttribute("selectedType", filterType);
        model.addAttribute("keyword", normalizedKeyword != null ? normalizedKeyword : "");
        model.addAttribute("page", queuePage.page());
        model.addAttribute("pageSize", safeSize);

        model.addAttribute("statuses", WikiContributionStatus.values());
        model.addAttribute("contributionTypes", WikiContributionType.values());

        return VIEW_NAME;
    }

    public String detailPage(
            UUID contributionId,
            Model model,
            HttpServletResponse response,
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes
    ) {
        return detailPage(contributionId, null, model, response, redirectAttributes);
    }

    @GetMapping("/{contributionId}")
    public String detailPage(
            @PathVariable UUID contributionId,
            HttpServletRequest request,
            Model model,
            HttpServletResponse response,
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes
    ) {
        if (response != null) {
            disableCaching(response);
        }

        try {
            com.universe.wiki.entry.admin.dto.AdminWikiContributionDetailDTO detail = coordinator.getDetail(contributionId);
            AuthenticatedRequestIdentity currentAdmin = request != null
                    ? AuthenticatedRequestIdentityAccessor.find(request).orElse(null)
                    : null;

            model.addAttribute("pageTitle", "Chi tiết đóng góp Wiki");
            model.addAttribute("activeMenu", ACTIVE_MENU);
            model.addAttribute("contribution", detail);
            model.addAttribute("currentAdmin", currentAdmin);

            return "admin/wiki/contribution-detail";
        } catch (com.universe.wiki.application.exceptions.WikiContributionNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy đóng góp bài viết Wiki: " + contributionId
            );
            return "redirect:/admin/wiki/contributions";
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }

    private String normalizeStatusString(String status) {
        if (status == null || status.isBlank()) {
            return WikiContributionStatus.NEW.name();
        }
        String trimmed = status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(trimmed)) {
            return "ALL";
        }
        try {
            return WikiContributionStatus.valueOf(trimmed).name();
        } catch (IllegalArgumentException ex) {
            return WikiContributionStatus.NEW.name();
        }
    }

    private WikiContributionStatus resolveFilterStatus(String normalizedStatusStr) {
        if ("ALL".equals(normalizedStatusStr)) {
            return null;
        }
        return WikiContributionStatus.valueOf(normalizedStatusStr);
    }

    private WikiContributionType resolveFilterType(String typeStr) {
        if (typeStr == null || typeStr.isBlank() || "ALL".equalsIgnoreCase(typeStr.trim())) {
            return null;
        }
        try {
            return WikiContributionType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }
}
