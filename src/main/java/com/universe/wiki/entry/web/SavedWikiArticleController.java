package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.saved.SaveWikiArticleCommand;
import com.universe.wiki.application.saved.SaveWikiArticleUseCase;
import com.universe.wiki.application.saved.UnsaveWikiArticleCommand;
import com.universe.wiki.application.saved.UnsaveWikiArticleUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Controller xử lý các thao tác lưu và bỏ lưu bài viết Wiki của người dùng đã xác thực.
 *
 * Quy tắc bảo mật:
 * 1. Mọi endpoint yêu cầu xác thực và CSRF token hợp lệ;
 * 2. Actor ID (userId) luôn được trích xuất an toàn từ AuthenticatedRequestIdentityAccessor,
 *    tuyệt đối không chấp nhận tham số định danh từ request body, header hay query params;
 * 3. Thành công trả về HTTP 204 No Content;
 * 4. Lưu bài viết chưa xuất bản (DRAFT, ARCHIVED, hoặc không tồn tại) trả về HTTP 404 Not Found
 *    mà không làm lộ chi tiết trạng thái bài viết;
 * 5. Thao tác lưu lại bài viết đã lưu hoặc bỏ lưu luôn đảm bảo tính lũy suy (idempotent), trả về 204 No Content.
 */
@Controller
@RequestMapping("/wiki")
public class SavedWikiArticleController {

    private final SaveWikiArticleUseCase saveWikiArticleUseCase;
    private final UnsaveWikiArticleUseCase unsaveWikiArticleUseCase;

    public SavedWikiArticleController(
            SaveWikiArticleUseCase saveWikiArticleUseCase,
            UnsaveWikiArticleUseCase unsaveWikiArticleUseCase
    ) {
        this.saveWikiArticleUseCase = Objects.requireNonNull(
                saveWikiArticleUseCase,
                "SaveWikiArticleUseCase không được để trống."
        );
        this.unsaveWikiArticleUseCase = Objects.requireNonNull(
                unsaveWikiArticleUseCase,
                "UnsaveWikiArticleUseCase không được để trống."
        );
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
