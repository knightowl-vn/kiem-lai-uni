package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.appreciation.SetWikiAppreciationCommand;
import com.universe.wiki.application.appreciation.SetWikiAppreciationResult;
import com.universe.wiki.application.appreciation.SetWikiAppreciationUseCase;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.entry.dto.appreciation.SetWikiAppreciationRequest;
import com.universe.wiki.entry.dto.appreciation.SetWikiAppreciationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller xử lý tương tác đánh giá mức độ yêu thích (Appreciation Rating) cho bài viết Wiki.
 *
 * <p>Quy tắc bảo mật và kiến trúc:
 * <ul>
 *   <li>Tuyến PUT /api/wiki/articles/{articleId}/appreciation yêu cầu xác thực và CSRF token hợp lệ;</li>
 *   <li>Actor ID (userId) luôn được trích xuất an toàn từ {@link AuthenticatedRequestIdentityAccessor},
 *       tuyệt đối không chấp nhận tham số định danh từ request body hay query params;</li>
 *   <li>Bài viết không tồn tại, chưa xuất bản (DRAFT, ARCHIVED) hoặc không đủ điều kiện (loại bài khác CHARACTER, FACTION)
 *       trả về HTTP 404 Not Found mà không làm lộ chi tiết nội bộ;</li>
 *   <li>Tham số điểm đánh giá (value) ngoài khoảng 1..5 hoặc bị thiếu/malformed trả về HTTP 400 Bad Request;</li>
 *   <li>Thành công trả về HTTP 200 OK kèm payload tổng hợp cộng đồng mới nhất;</li>
 *   <li>Thao tác cùng giá trị (same-value) đảm bảo idempotent và trả về HTTP 200 OK với changed=false.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/wiki/articles")
public class WikiArticleAppreciationController {

    private final SetWikiAppreciationUseCase setWikiAppreciationUseCase;

    public WikiArticleAppreciationController(SetWikiAppreciationUseCase setWikiAppreciationUseCase) {
        this.setWikiAppreciationUseCase = Objects.requireNonNull(
                setWikiAppreciationUseCase,
                "SetWikiAppreciationUseCase không được để trống."
        );
    }

    @PutMapping("/{articleId}/appreciation")
    public ResponseEntity<SetWikiAppreciationResponse> setAppreciation(
            @PathVariable UUID articleId,
            @RequestBody(required = false) SetWikiAppreciationRequest requestBody,
            HttpServletRequest request
    ) {
        if (articleId == null || requestBody == null || requestBody.value() == null) {
            return ResponseEntity.badRequest().build();
        }

        if (requestBody.value() < WikiAppreciationRating.MIN_VALUE || requestBody.value() > WikiAppreciationRating.MAX_VALUE) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID actorUserId = identityOptional.get().userId();

        SetWikiAppreciationResult result = setWikiAppreciationUseCase.execute(
                new SetWikiAppreciationCommand(
                        articleId,
                        actorUserId,
                        requestBody.value()
                )
        );

        return ResponseEntity.ok(SetWikiAppreciationResponse.from(result));
    }

    @ExceptionHandler(WikiAppreciationTargetNotFoundException.class)
    public ResponseEntity<Void> handleTargetNotFound(WikiAppreciationTargetNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Void> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().build();
    }
}
