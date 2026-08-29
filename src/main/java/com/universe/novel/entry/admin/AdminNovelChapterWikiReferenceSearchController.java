package com.universe.novel.entry.admin;

import com.universe.novel.application.chapter.reference.SearchTargetWikiArticlesUseCase;
import com.universe.novel.application.chapter.reference.TargetWikiArticleSearchResultDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller phục vụ tìm kiếm các bài viết Wiki đã xuất bản
 * để gán liên kết tham chiếu trong quản trị Chapter (MS-02.8.1 Step 6D1).
 */
@RestController
@RequestMapping("/admin/novel/chapters/{chapterId}/wiki-references")
public class AdminNovelChapterWikiReferenceSearchController {

    private final SearchTargetWikiArticlesUseCase searchTargetWikiArticlesUseCase;

    public AdminNovelChapterWikiReferenceSearchController(
            SearchTargetWikiArticlesUseCase searchTargetWikiArticlesUseCase
    ) {
        this.searchTargetWikiArticlesUseCase = Objects.requireNonNull(
                searchTargetWikiArticlesUseCase,
                "SearchTargetWikiArticlesUseCase không được để trống."
        );
    }

    /**
     * GET /admin/novel/chapters/{chapterId}/wiki-references/search-targets?q={query}
     * Tìm kiếm danh sách bài viết Wiki đã xuất bản (PUBLISHED) theo từ khóa.
     *
     * @param chapterId ID của chương đang quản lý
     * @param query từ khóa tra cứu (1..100 ký tự)
     * @return 200 OK với JSON kết quả tìm kiếm bài viết Wiki
     */
    @GetMapping("/search-targets")
    public ResponseEntity<TargetWikiArticleSearchResultDTO> searchTargets(
            @PathVariable UUID chapterId,
            @RequestParam(name = "q", required = false) String query
    ) {
        TargetWikiArticleSearchResultDTO result = searchTargetWikiArticlesUseCase.execute(query);
        return ResponseEntity.ok(result);
    }
}
