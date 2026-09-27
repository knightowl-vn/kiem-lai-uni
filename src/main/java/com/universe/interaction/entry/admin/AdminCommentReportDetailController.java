package com.universe.interaction.entry.admin;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.entry.admin.dto.AdminCommentReportContextNavigationDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Objects;
import java.util.UUID;

/**
 * Admin controller for comment report moderation detail and live discussion navigation.
 *
 * <p>Handles path variable binding, delegates composition to {@link AdminCommentReportDetailCoordinator}
 * and navigation resolution to {@link AdminCommentReportContextNavigationCoordinator},
 * maps domain article types to public URL representations via {@link ArticleTypePathMapper},
 * applies HTTP no-cache directives, and enforces standard human-navigation redirects.
 */
@Controller
public class AdminCommentReportDetailController {

    private static final String VIEW_NAME = "admin/comments/report-detail";
    private static final String PAGE_TITLE = "Chi tiết báo cáo bình luận";
    private static final String ACTIVE_MENU = "comment-reports";

    private static final String ERROR_CONTEXT_UNAVAILABLE = "Không thể mở bình luận trong ngữ cảnh hiện tại.";

    private final AdminCommentReportDetailCoordinator coordinator;
    private final AdminCommentReportContextNavigationCoordinator navigationCoordinator;
    private final ArticleTypePathMapper articleTypePathMapper;

    public AdminCommentReportDetailController(
            AdminCommentReportDetailCoordinator coordinator,
            AdminCommentReportContextNavigationCoordinator navigationCoordinator,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        this.coordinator = Objects.requireNonNull(
                coordinator,
                "AdminCommentReportDetailCoordinator cannot be null"
        );
        this.navigationCoordinator = Objects.requireNonNull(
                navigationCoordinator,
                "AdminCommentReportContextNavigationCoordinator cannot be null"
        );
        this.articleTypePathMapper = Objects.requireNonNull(
                articleTypePathMapper,
                "ArticleTypePathMapper cannot be null"
        );
    }

    @GetMapping("/admin/comments/reports/{reportId}")
    public String reportDetail(
            @PathVariable UUID reportId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        if (response != null) {
            disableCaching(response);
        }

        try {
            AdminCommentReportDetailDTO report = coordinator.getDetail(reportId);

            model.addAttribute("report", report);
            model.addAttribute("pageTitle", PAGE_TITLE);
            model.addAttribute("activeMenu", ACTIVE_MENU);

            return VIEW_NAME;
        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy báo cáo: " + reportId
            );
            return "redirect:/admin/comments/reports";
        }
    }

    @GetMapping("/admin/comments/reports/{reportId}/context")
    public String navigateToContext(
            @PathVariable UUID reportId,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        if (response != null) {
            disableCaching(response);
        }

        try {
            AdminCommentReportContextNavigationDTO nav = navigationCoordinator.resolveNavigation(reportId);

            if (!nav.available()) {
                redirectAttributes.addFlashAttribute("errorMessage", ERROR_CONTEXT_UNAVAILABLE);
                return "redirect:/admin/comments/reports/" + reportId;
            }

            if (nav.targetType() == CommentTargetType.NOVEL_CHAPTER) {
                String destination = UriComponentsBuilder.fromPath("/novel/chapters/{slug}")
                        .queryParam("commentId", nav.commentId())
                        .queryParam("threadId", nav.threadId())
                        .fragment("novelChapterComments")
                        .buildAndExpand(nav.slug())
                        .toUriString();
                return "redirect:" + destination;
            }

            if (nav.targetType() == CommentTargetType.WIKI_ARTICLE) {
                if (nav.articleType() == null || nav.articleType().isBlank()) {
                    redirectAttributes.addFlashAttribute("errorMessage", ERROR_CONTEXT_UNAVAILABLE);
                    return "redirect:/admin/comments/reports/" + reportId;
                }

                ArticleType articleType;
                try {
                    articleType = ArticleType.valueOf(nav.articleType().trim());
                } catch (IllegalArgumentException ex) {
                    redirectAttributes.addFlashAttribute("errorMessage", ERROR_CONTEXT_UNAVAILABLE);
                    return "redirect:/admin/comments/reports/" + reportId;
                }

                String mappedPath = articleTypePathMapper.toPath(articleType);

                String destination = UriComponentsBuilder.fromPath("/wiki/{articleType}/{slug}")
                        .queryParam("commentId", nav.commentId())
                        .queryParam("threadId", nav.threadId())
                        .fragment("wikiDiscussion")
                        .buildAndExpand(mappedPath, nav.slug())
                        .toUriString();
                return "redirect:" + destination;
            }

            redirectAttributes.addFlashAttribute("errorMessage", ERROR_CONTEXT_UNAVAILABLE);
            return "redirect:/admin/comments/reports/" + reportId;

        } catch (InteractionReportNotFoundException ex) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Không tìm thấy báo cáo: " + reportId
            );
            return "redirect:/admin/comments/reports";
        }
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0L);
    }
}
