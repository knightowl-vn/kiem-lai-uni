package com.universe.novel.entry.admin;

import com.universe.novel.application.narration.AdminChapterNarrationStatusDTO;
import com.universe.novel.application.narration.AdminNarrationGenerationDispatcher;
import com.universe.novel.application.narration.AdminNarrationOperationState;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewResult;
import com.universe.novel.application.narration.GetAdminChapterNarrationOverviewUseCase;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller serving the read-only Admin chapter narration overview page (MS-04.9H.6A)
 * and read-only JSON status endpoint for background generation progress polling (MS-04.9H.7D4B).
 */
@Controller
@RequestMapping("/admin/novel/chapters")
public class AdminNovelChapterNarrationPageController {

    private static final String ACTIVE_MENU = "novel";

    private final GetAdminChapterNarrationOverviewUseCase overviewUseCase;
    private final AdminNarrationGenerationDispatcher dispatcher;

    public AdminNovelChapterNarrationPageController(
            GetAdminChapterNarrationOverviewUseCase overviewUseCase,
            AdminNarrationGenerationDispatcher dispatcher
    ) {
        this.overviewUseCase = Objects.requireNonNull(overviewUseCase, "overviewUseCase must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
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

        AdminNarrationOperationState operationState = overview.selectedVoice() != null
                ? dispatcher.getOperationState(chapterId, overview.selectedVoice().id())
                : AdminNarrationOperationState.idle(chapterId, new UUID(0L, 0L));

        model.addAttribute("chapter", overview.chapter());
        model.addAttribute("volume", overview.volume());
        model.addAttribute("voices", overview.voices());
        model.addAttribute("selectedVoice", overview.selectedVoice());
        model.addAttribute("segments", overview.segments());
        model.addAttribute("totalSegments", overview.totalSegments());
        model.addAttribute("currentSegmentCount", overview.currentSegmentCount());
        model.addAttribute("readyCount", overview.readyCount());
        model.addAttribute("outdatedCount", overview.outdatedCount());
        model.addAttribute("missingCount", overview.missingCount());
        model.addAttribute("failedCount", overview.failedCount());
        model.addAttribute("currentGenerationRequiredCount", overview.currentGenerationRequiredCount());
        model.addAttribute("retiredSegmentCount", overview.retiredSegmentCount());
        model.addAttribute("obsoleteRetiredSegmentCount", overview.obsoleteRetiredSegmentCount());
        model.addAttribute("obsoleteRetiredAudioCount", overview.obsoleteRetiredAudioCount());
        model.addAttribute("contentChangeWarning", overview.contentChangeWarning());
        model.addAttribute("chapterPlayback", overview.chapterPlayback());
        model.addAttribute("operationState", operationState);
        model.addAttribute("isOperationRunning", operationState.isRunning());
        model.addAttribute("pageTitle", "Quản lý giọng đọc Chapter");
        model.addAttribute("activeMenu", ACTIVE_MENU);

        return "admin/novel/chapter-narration";
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/narration/status?voiceId={voiceId}
     */
    @GetMapping("/{chapterId}/narration/status")
    @ResponseBody
    public ResponseEntity<AdminChapterNarrationStatusDTO> narrationStatus(
            @PathVariable UUID chapterId,
            @RequestParam(required = false) UUID voiceId,
            HttpServletResponse response
    ) {
        disableCaching(response);

        GetAdminChapterNarrationOverviewResult overview = overviewUseCase.execute(chapterId, voiceId);

        AdminNarrationOperationState operationState = overview.selectedVoice() != null
                ? dispatcher.getOperationState(chapterId, overview.selectedVoice().id())
                : AdminNarrationOperationState.idle(chapterId, new UUID(0L, 0L));

        AdminChapterNarrationStatusDTO dto = new AdminChapterNarrationStatusDTO(
                operationState.status().name(),
                operationState.message(),
                operationState.startedAt(),
                operationState.completedAt(),
                overview.currentSegmentCount(),
                overview.readyCount(),
                overview.outdatedCount(),
                overview.missingCount(),
                overview.failedCount(),
                overview.currentGenerationRequiredCount(),
                overview.contentChangeWarning(),
                overview.obsoleteRetiredAudioCount()
        );

        return ResponseEntity.ok(dto);
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}
