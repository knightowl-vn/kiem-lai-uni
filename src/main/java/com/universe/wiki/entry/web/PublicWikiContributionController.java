package com.universe.wiki.entry.web;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor;
import com.universe.wiki.application.contribution.SubmitWikiContributionCommand;
import com.universe.wiki.application.contribution.SubmitWikiContributionResult;
import com.universe.wiki.application.contribution.SubmitWikiContributionUseCase;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionSubmissionRateLimitedException;
import com.universe.wiki.entry.web.dto.SubmitWikiContributionRequest;
import com.universe.wiki.entry.web.dto.SubmitWikiContributionResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller công khai tiếp nhận đóng góp ý kiến / chỉnh sửa bài viết Wiki từ độc giả đã xác thực.
 *
 * Quy tắc bảo mật và kiến trúc:
 * 1. Tuyến POST /wiki/articles/{articleId}/contributions yêu cầu xác thực và CSRF token hợp lệ;
 * 2. Actor ID (submittedByUserId) luôn được trích xuất an toàn từ AuthenticatedRequestIdentityAccessor;
 * 3. Article ID từ URI (@PathVariable) là căn cứ duy nhất, request body không được ghi đè;
 * 4. Bài viết không tồn tại hoặc chưa xuất bản trả về HTTP 404 Not Found;
 * 5. Dữ liệu không hợp lệ (version > current, thông điệp sai định dạng, >5 nguồn...) trả về HTTP 400 Bad Request;
 * 6. Đóng góp mới trả về HTTP 201 Created;
 * 7. Phát hiện trùng lặp trong 60s trả về bản ghi hiện tại kèm alreadySubmitted=true và HTTP 200 OK.
 */
@RestController
@RequestMapping("/wiki/articles")
public class PublicWikiContributionController {

    private final SubmitWikiContributionUseCase submitWikiContributionUseCase;

    public PublicWikiContributionController(SubmitWikiContributionUseCase submitWikiContributionUseCase) {
        this.submitWikiContributionUseCase = Objects.requireNonNull(
                submitWikiContributionUseCase,
                "SubmitWikiContributionUseCase không được để trống."
        );
    }

    @PostMapping("/{articleId}/contributions")
    public ResponseEntity<SubmitWikiContributionResponse> submitContribution(
            @PathVariable UUID articleId,
            @RequestBody(required = false) SubmitWikiContributionRequest requestBody,
            HttpServletRequest request
    ) {
        if (articleId == null || requestBody == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<AuthenticatedRequestIdentity> identityOptional =
                AuthenticatedRequestIdentityAccessor.find(request);
        if (identityOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID submittedByUserId = identityOptional.get().userId();

        SubmitWikiContributionResult result = submitWikiContributionUseCase.execute(
                new SubmitWikiContributionCommand(
                        articleId,
                        submittedByUserId,
                        requestBody.articleContentVersion(),
                        requestBody.contextType(),
                        requestBody.contributionType(),
                        requestBody.message(),
                        requestBody.selectedText(),
                        requestBody.selectedPrefix(),
                        requestBody.selectedSuffix(),
                        requestBody.selectedHeadingAnchor(),
                        requestBody.sources()
                )
        );

        SubmitWikiContributionResponse response = new SubmitWikiContributionResponse(
                result.contributionId(),
                result.status(),
                result.alreadySubmitted(),
                result.message()
        );

        if (result.alreadySubmitted()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }

    @ExceptionHandler(WikiContributionSubmissionRateLimitedException.class)
    public ResponseEntity<SubmitWikiContributionResponse> handleRateLimited(WikiContributionSubmissionRateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(new SubmitWikiContributionResponse(
                        null,
                        null,
                        false,
                        "Bạn đang gửi đóng góp quá nhanh. Vui lòng thử lại sau ít phút."
                ));
    }

    @ExceptionHandler(PublishedWikiArticleNotFoundException.class)
    public ResponseEntity<Void> handleArticleNotFound(PublishedWikiArticleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Void> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().build();
    }
}
