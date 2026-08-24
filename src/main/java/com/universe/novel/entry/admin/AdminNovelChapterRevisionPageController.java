package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.revision.GetChapterRevisionDetailUseCase;
import com.universe.novel.application.chapter.revision.ListChapterRevisionsUseCase;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/admin/novel/chapters")
public class AdminNovelChapterRevisionPageController {

    private static final String ACTIVE_MENU =
            "novel";

    private static final int DEFAULT_PAGE_SIZE =
            20;

    private final ListChapterRevisionsUseCase
            listChapterRevisionsUseCase;

    private final GetChapterRevisionDetailUseCase
            getChapterRevisionDetailUseCase;

    private final GetChapterDetailUseCase
            getChapterDetailUseCase;

    private final GetVolumeDetailUseCase
            getVolumeDetailUseCase;

    public AdminNovelChapterRevisionPageController(
            ListChapterRevisionsUseCase listChapterRevisionsUseCase,
            GetChapterRevisionDetailUseCase getChapterRevisionDetailUseCase,
            GetChapterDetailUseCase getChapterDetailUseCase,
            GetVolumeDetailUseCase getVolumeDetailUseCase
    ) {
        this.listChapterRevisionsUseCase =
                Objects.requireNonNull(
                        listChapterRevisionsUseCase,
                        "ListChapterRevisionsUseCase không được để trống."
                );

        this.getChapterRevisionDetailUseCase =
                Objects.requireNonNull(
                        getChapterRevisionDetailUseCase,
                        "GetChapterRevisionDetailUseCase không được để trống."
                );

        this.getChapterDetailUseCase =
                Objects.requireNonNull(
                        getChapterDetailUseCase,
                        "GetChapterDetailUseCase không được để trống."
                );

        this.getVolumeDetailUseCase =
                Objects.requireNonNull(
                        getVolumeDetailUseCase,
                        "GetVolumeDetailUseCase không được để trống."
                );
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/revisions
     */
    @GetMapping("/{chapterId}/revisions")
    public String listRevisionsPage(
            @PathVariable UUID chapterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(
                response
        );

        ChapterDTO chapter =
                getChapterDetailUseCase.execute(
                        chapterId
                );

        VolumeDTO volume =
                getVolumeDetailUseCase.execute(
                        chapter.volumeId()
                );

        ChapterRevisionListPageDTO pageResult =
                listChapterRevisionsUseCase.execute(
                        chapterId,
                        page,
                        size
                );

        model.addAttribute(
                "chapter",
                chapter
        );

        model.addAttribute(
                "volume",
                volume
        );

        model.addAttribute(
                "pageResult",
                pageResult
        );

        model.addAttribute(
                "pageTitle",
                "Lịch sử chỉnh sửa"
        );

        model.addAttribute(
                "activeMenu",
                ACTIVE_MENU
        );

        return "admin/novel/chapter-revisions";
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/revisions/{revisionNumber}
     */
    @GetMapping("/{chapterId}/revisions/{revisionNumber}")
    public String revisionDetailPage(
            @PathVariable UUID chapterId,
            @PathVariable long revisionNumber,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(
                response
        );

        ChapterDTO chapter =
                getChapterDetailUseCase.execute(
                        chapterId
                );

        VolumeDTO volume =
                getVolumeDetailUseCase.execute(
                        chapter.volumeId()
                );

        ChapterRevisionDetailDTO revision =
                getChapterRevisionDetailUseCase.execute(
                        chapterId,
                        revisionNumber
                );

        model.addAttribute(
                "chapter",
                chapter
        );

        model.addAttribute(
                "volume",
                volume
        );

        model.addAttribute(
                "revision",
                revision
        );

        model.addAttribute(
                "pageTitle",
                "Chi tiết phiên bản"
        );

        model.addAttribute(
                "activeMenu",
                ACTIVE_MENU
        );

        return "admin/novel/chapter-revision-detail";
    }

    private void disableCaching(
            HttpServletResponse response
    ) {
        response.setHeader(
                "Cache-Control",
                "no-store, no-cache, must-revalidate, max-age=0"
        );

        response.setHeader(
                "Pragma",
                "no-cache"
        );

        response.setDateHeader(
                "Expires",
                0
        );
    }
}
