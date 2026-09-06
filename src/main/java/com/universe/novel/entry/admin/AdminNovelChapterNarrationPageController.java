package com.universe.novel.entry.admin;

import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewUseCase;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller serving the read-only Admin chapter narration overview page (MS-04.9H.6A).
 */
@Controller
@RequestMapping("/admin/novel/chapters")
public class AdminNovelChapterNarrationPageController {

    private static final String ACTIVE_MENU = "novel";

    private final GetAdminChapterNarrationOverviewUseCase overviewUseCase;

    public AdminNovelChapterNarrationPageController(
            GetAdminChapterNarrationOverviewUseCase overviewUseCase
    ) {
        this.overviewUseCase = Objects.requireNonNull(overviewUseCase, "overviewUseCase must not be null");
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/narration
     */
    @GetMapping("/{chapterId}/narration")
    public String narrationOverviewPage(
            @PathVariable UUID chapterId,
            @RequestParam(required = false) UUID voiceId,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(response);

        GetAdminChapterNarrationOverviewResult overview = overviewUseCase.execute(chapterId, voiceId);

        model.addAttribute("chapter", overview.chapter());
        model.addAttribute("volume", overview.volume());
        model.addAttribute("voices", overview.voices());
        model.addAttribute("selectedVoice", overview.selectedVoice());
        model.addAttribute("segments", overview.segments());
        model.addAttribute("totalSegments", overview.totalSegments());
        model.addAttribute("readyCount", overview.readyCount());
        model.addAttribute("outdatedCount", overview.outdatedCount());
        model.addAttribute("missingCount", overview.missingCount());
        model.addAttribute("failedCount", overview.failedCount());
        model.addAttribute("pageTitle", "Quản lý giọng đọc Chapter");
        model.addAttribute("activeMenu", ACTIVE_MENU);

        return "admin/novel/chapter-narration";
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}
