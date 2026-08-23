package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.GetChapterListUseCase;
import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.application.volume.GetVolumeListUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.novel.contracts.dto.ChapterListPageDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.entry.admin.form.CreateChapterForm;
import com.universe.novel.entry.admin.form.EditChapterForm;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin/novel")
public class AdminNovelChapterPageController {

    private static final String ACTIVE_MENU =
            "novel";

    private static final int DEFAULT_PAGE_SIZE =
            50;

    private final GetChapterListUseCase
            getChapterListUseCase;

    private final GetChapterDetailUseCase
            getChapterDetailUseCase;

    private final GetVolumeDetailUseCase
            getVolumeDetailUseCase;

    private final GetVolumeListUseCase
            getVolumeListUseCase;

    private final NovelMarkdownRenderer
            novelMarkdownRenderer;

    public AdminNovelChapterPageController(
            GetChapterListUseCase getChapterListUseCase,
            GetChapterDetailUseCase getChapterDetailUseCase,
            GetVolumeDetailUseCase getVolumeDetailUseCase,
            GetVolumeListUseCase getVolumeListUseCase,
            NovelMarkdownRenderer novelMarkdownRenderer
    ) {
        this.getChapterListUseCase =
                getChapterListUseCase;

        this.getChapterDetailUseCase =
                getChapterDetailUseCase;

        this.getVolumeDetailUseCase =
                getVolumeDetailUseCase;

        this.getVolumeListUseCase =
                getVolumeListUseCase;

        this.novelMarkdownRenderer =
                novelMarkdownRenderer;
    }

    /**
     * GET /admin/novel/volumes/{volumeId}/chapters
     */
    @GetMapping("/volumes/{volumeId}/chapters")
    public String listPage(
            @PathVariable UUID volumeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(
                response
        );

        VolumeDTO volume =
                getVolumeDetailUseCase.execute(
                        volumeId
                );

        String normalizedKeyword =
                NovelAdminListFilters.normalizeKeyword(
                        keyword
                );

        String selectedStatus =
                NovelAdminListFilters.normalizeStatus(
                        status
                );

        ChapterListPageDTO pageResult =
                getChapterListUseCase.execute(
                        volumeId,
                        normalizedKeyword,
                        selectedStatus,
                        page,
                        DEFAULT_PAGE_SIZE
                );

        List<ChapterListItemDTO> chapters =
                pageResult.items();

        model.addAttribute(
                "volume",
                volume
        );

        model.addAttribute(
                "chapters",
                chapters
        );

        model.addAttribute(
                "pageResult",
                pageResult
        );

        model.addAttribute(
                "volumes",
                getVolumeListUseCase.execute()
        );

        model.addAttribute(
                "keyword",
                normalizedKeyword
        );

        model.addAttribute(
                "selectedStatus",
                selectedStatus
        );

        model.addAttribute(
                "pageTitle",
                "Quản lý chương"
        );

        model.addAttribute(
                "activeMenu",
                ACTIVE_MENU
        );

        return "admin/novel/chapters";
    }

    /**
     * GET /admin/novel/volumes/{volumeId}/chapters/new
     */
    @GetMapping("/volumes/{volumeId}/chapters/new")
    public String createPage(
            @PathVariable UUID volumeId,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(
                response
        );

        VolumeDTO volume =
                getVolumeDetailUseCase.execute(
                        volumeId
                );

        model.addAttribute(
                "volume",
                volume
        );

        model.addAttribute(
                "form",
                new CreateChapterForm()
        );

        model.addAttribute(
                "pageTitle",
                "Tạo Chapter"
        );

        model.addAttribute(
                "activeMenu",
                ACTIVE_MENU
        );

        return "admin/novel/chapter-create";
    }

    /**
     * GET /admin/novel/chapters/{chapterId}
     */
    @GetMapping("/chapters/{chapterId}")
    public String detailPage(
            @PathVariable UUID chapterId,
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

        model.addAttribute(
                "chapter",
                chapter
        );

        model.addAttribute(
                "volume",
                volume
        );

        model.addAttribute(
                "pageTitle",
                "Chi tiết Chapter"
        );

        model.addAttribute(
                "activeMenu",
                ACTIVE_MENU
        );

        return "admin/novel/chapter-detail";
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/edit
     */
    @GetMapping("/chapters/{chapterId}/edit")
    public String editPage(
            @PathVariable UUID chapterId,
            Model model,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {
        disableCaching(
                response
        );

        ChapterDTO chapter =
                getChapterDetailUseCase.execute(
                        chapterId
                );

        if (!"DRAFT".equals(
                chapter.status()
        )) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Chapter không còn ở trạng thái DRAFT nên không thể chỉnh sửa."
            );

            return "redirect:/admin/novel/chapters/"
                    + chapterId;
        }

        VolumeDTO volume =
                getVolumeDetailUseCase.execute(
                        chapter.volumeId()
                );

        EditChapterForm form =
                new EditChapterForm();

        form.setChapterNumber(
                chapter.chapterNumber()
        );

        form.setTitle(
                chapter.title()
        );

        form.setSummary(
                chapter.summary()
        );

        form.setContent(
                chapter.content()
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
                "form",
                form
        );

        model.addAttribute(
                "pageTitle",
                "Chỉnh sửa Chapter"
        );

        model.addAttribute(
                "activeMenu",
                ACTIVE_MENU
        );

        return "admin/novel/chapter-edit";
    }

    /**
     * POST /admin/novel/chapters/content-preview
     */
    @PostMapping(
            value = "/chapters/content-preview",
            consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.TEXT_HTML_VALUE
    )
    @ResponseBody
    public String previewChapterContent(
            @RequestBody(required = false) String markdown
    ) {
        return novelMarkdownRenderer.renderToHtml(
                markdown
        );
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