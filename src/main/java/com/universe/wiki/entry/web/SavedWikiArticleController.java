package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.saved.ListSavedWikiArticlesUseCase;
import com.universe.wiki.application.saved.SaveWikiArticleCommand;
import com.universe.wiki.application.saved.SaveWikiArticleUseCase;
import com.universe.wiki.application.saved.UnsaveWikiArticleCommand;
import com.universe.wiki.application.saved.UnsaveWikiArticleUseCase;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticleItemDTO;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.entry.web.support.ArticleTypePathMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller xử lý các thao tác lưu, bỏ lưu và xem danh sách bài viết Wiki đã lưu của người dùng đã xác thực.
 *
 * Quy tắc bảo mật & hiệu năng:
 * 1. Mọi endpoint yêu cầu xác thực và CSRF token hợp lệ;
 * 2. Actor ID (userId) luôn được trích xuất an toàn từ AuthenticatedRequestIdentityAccessor,
 *    tuyệt đối không chấp nhận tham số định danh từ request body, header hay query params;
 * 3. Thành công trả về HTTP 204 No Content cho thao tác mutation;
 * 4. Lưu bài viết chưa xuất bản (DRAFT, ARCHIVED, hoặc không tồn tại) trả về HTTP 404 Not Found
 *    mà không làm lộ chi tiết trạng thái bài viết;
 * 5. Thao tác lưu lại bài viết đã lưu hoặc bỏ lưu luôn đảm bảo tính lũy suy (idempotent), trả về 204 No Content;
 * 6. Tuyến GET /wiki/saved hiển thị danh sách bài viết đã lưu có phân trang (kích thước mặc định 20),
 *    sử dụng ArticleTypePathMapper để giải quyết canonical articleTypePath cho các bài viết khả dụng,
 *    đồng thời giải quyết hàng loạt (batch) bản tóm tắt đánh giá (appreciation summaries) cho các bài viết đủ điều kiện.
 */
@Controller
@RequestMapping("/wiki")
public class SavedWikiArticleController {

    private final SaveWikiArticleUseCase saveWikiArticleUseCase;
    private final UnsaveWikiArticleUseCase unsaveWikiArticleUseCase;
    private final ListSavedWikiArticlesUseCase listSavedWikiArticlesUseCase;
    private final GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase;
    private final ArticleTypePathMapper articleTypePathMapper;

    public SavedWikiArticleController(
            SaveWikiArticleUseCase saveWikiArticleUseCase,
            UnsaveWikiArticleUseCase unsaveWikiArticleUseCase,
            ListSavedWikiArticlesUseCase listSavedWikiArticlesUseCase,
            GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        this.saveWikiArticleUseCase = Objects.requireNonNull(
                saveWikiArticleUseCase,
                "SaveWikiArticleUseCase không được để trống."
        );
        this.unsaveWikiArticleUseCase = Objects.requireNonNull(
                unsaveWikiArticleUseCase,
                "UnsaveWikiArticleUseCase không được để trống."
        );
        this.listSavedWikiArticlesUseCase = Objects.requireNonNull(
                listSavedWikiArticlesUseCase,
                "ListSavedWikiArticlesUseCase không được để trống."
        );
        this.getWikiAppreciationSummariesUseCase = Objects.requireNonNull(
                getWikiAppreciationSummariesUseCase,
                "GetWikiAppreciationSummariesUseCase không được để trống."
        );
        this.articleTypePathMapper = Objects.requireNonNull(
                articleTypePathMapper,
                "ArticleTypePathMapper không được để trống."
        );
    }

    @GetMapping("/saved")
    public String savedArticlesPage(
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model
    ) {
        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return "redirect:/login";
        }

        int normalizedPage = Math.max(0, page);
        SavedWikiArticlePageDTO pageDto = listSavedWikiArticlesUseCase.execute(
                identityOptional.get().userId(),
                normalizedPage,
                ListSavedWikiArticlesUseCase.DEFAULT_PAGE_SIZE
        );

        SavedWikiArticlePageViewModel savedPage = SavedWikiArticlePageViewModel.from(
                pageDto,
                articleTypePathMapper
        );

        List<UUID> eligibleArticleIds = pageDto.items().stream()
                .filter(SavedWikiArticleItemDTO::available)
                .filter(item -> ArticleType.CHARACTER.name().equals(item.articleType())
                        || ArticleType.FACTION.name().equals(item.articleType()))
                .map(SavedWikiArticleItemDTO::articleId)
                .toList();

        Map<UUID, WikiAppreciationSummary> appreciationSummaries = eligibleArticleIds.isEmpty()
                ? Collections.emptyMap()
                : getWikiAppreciationSummariesUseCase.execute(eligibleArticleIds);

        model.addAttribute("savedPage", savedPage);
        model.addAttribute("appreciationSummaries", appreciationSummaries);
        model.addAttribute("pageTitle", "Bài viết Wiki đã lưu");

        return "wiki/public/saved";
    }

    @PostMapping("/articles/{articleId}/save")
    @ResponseBody
    public ResponseEntity<Void> saveArticle(
            @PathVariable UUID articleId,
            HttpServletRequest request
    ) {
        if (articleId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            saveWikiArticleUseCase.execute(
                    new SaveWikiArticleCommand(
                            identityOptional.get().userId(),
                            articleId
                    )
            );
            return ResponseEntity.noContent().build();
        } catch (PublishedWikiArticleNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @DeleteMapping("/articles/{articleId}/save")
    @ResponseBody
    public ResponseEntity<Void> unsaveArticle(
            @PathVariable UUID articleId,
            HttpServletRequest request
    ) {
        if (articleId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        unsaveWikiArticleUseCase.execute(
                new UnsaveWikiArticleCommand(
                        identityOptional.get().userId(),
                        articleId
                )
        );
        return ResponseEntity.noContent().build();
    }
}
