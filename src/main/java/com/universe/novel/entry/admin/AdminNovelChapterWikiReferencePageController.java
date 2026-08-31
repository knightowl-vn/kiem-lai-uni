package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.GetChapterDetailUseCase;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceListPageDTO;
import com.universe.novel.application.chapter.reference.ListChapterWikiReferencesUseCase;
import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.volume.GetVolumeDetailUseCase;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.contracts.dto.VolumeDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller phục vụ trang giao diện quản trị liên kết Wiki cho Chapter (MS-02.8.1).
 */
@Controller
@RequestMapping("/admin/novel/chapters")
public class AdminNovelChapterWikiReferencePageController {

    private static final String ACTIVE_MENU = "novel";

    private final GetChapterDetailUseCase getChapterDetailUseCase;
    private final GetVolumeDetailUseCase getVolumeDetailUseCase;
    private final ListChapterWikiReferencesUseCase listChapterWikiReferencesUseCase;
    private final NovelMarkdownRenderer novelMarkdownRenderer;

    public AdminNovelChapterWikiReferencePageController(
            GetChapterDetailUseCase getChapterDetailUseCase,
            GetVolumeDetailUseCase getVolumeDetailUseCase,
            ListChapterWikiReferencesUseCase listChapterWikiReferencesUseCase,
            NovelMarkdownRenderer novelMarkdownRenderer
    ) {
        this.getChapterDetailUseCase = Objects.requireNonNull(
                getChapterDetailUseCase,
                "GetChapterDetailUseCase không được để trống."
        );
        this.getVolumeDetailUseCase = Objects.requireNonNull(
                getVolumeDetailUseCase,
                "GetVolumeDetailUseCase không được để trống."
        );
        this.listChapterWikiReferencesUseCase = Objects.requireNonNull(
                listChapterWikiReferencesUseCase,
                "ListChapterWikiReferencesUseCase không được để trống."
        );
        this.novelMarkdownRenderer = Objects.requireNonNull(
                novelMarkdownRenderer,
                "NovelMarkdownRenderer không được để trống."
        );
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/wiki-references
     * Hiển thị danh sách liên kết Wiki của chapter kèm bản xem trước nội dung HTML.
     */
    @GetMapping("/{chapterId}/wiki-references")
    public String listWikiReferencesPage(
            @PathVariable UUID chapterId,
            Model model,
            HttpServletResponse response
    ) {
        disableCaching(response);

        ChapterDTO chapter = getChapterDetailUseCase.execute(chapterId);
        VolumeDTO volume = getVolumeDetailUseCase.execute(chapter.volumeId());
        ChapterWikiReferenceListPageDTO pageResult = listChapterWikiReferencesUseCase.execute(chapterId);

        List<AdminChapterWikiReferenceViewItem> referenceItems = pageResult.references().stream()
                .map(AdminChapterWikiReferenceViewItem::from)
                .toList();

        String contentHtml = novelMarkdownRenderer.renderToHtml(chapter.content());

        model.addAttribute("chapter", chapter);
        model.addAttribute("volume", volume);
        model.addAttribute("pageResult", pageResult);
        model.addAttribute("referenceItems", referenceItems);
        model.addAttribute("contentHtml", contentHtml);
        model.addAttribute("pageTitle", "Quản lý liên kết Wiki");
        model.addAttribute("activeMenu", ACTIVE_MENU);

        return "admin/novel/chapter-wiki-references";
    }

    private void disableCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}
