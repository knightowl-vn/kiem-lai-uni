package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.exceptions.DuplicateWikiAppreciationException;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleEligibilitySnapshot;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Use case thực hiện điều phối thiết lập hoặc cập nhật đánh giá mức độ yêu thích (Appreciation Rating)
 * của người dùng đối với một bài viết Wiki (Nhân vật hoặc Thế lực/Tông môn).
 *
 * <p>Kiến trúc phi giao dịch (non-transactional orchestrator):
 * <ul>
 *   <li>1. Kiểm tra tính hợp lệ của bài viết Wiki đúng một lần trước các nỗ lực ghi nhận;</li>
 *   <li>2. Ủy quyền thực thi ghi nhận persistence cho {@link SetWikiAppreciationAttemptExecutor} chạy trong transaction độc lập (REQUIRES_NEW);</li>
 *   <li>3. Phục hồi tối đa một lần (bounded retry, MAX_ATTEMPTS=2) khi xảy ra ngoại lệ xung đột đồng thời {@link DuplicateWikiAppreciationException};</li>
 *   <li>4. Sau khi transaction của attempt đã commit thành công, truy vấn dữ liệu summary cộng đồng trực tiếp từ SQL;</li>
 *   <li>5. Trả về {@link SetWikiAppreciationResult} hoàn chỉnh.</li>
 * </ul>
 */
@Service
public class SetWikiAppreciationUseCase {

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final SetWikiAppreciationAttemptExecutor attemptExecutor;
    private final WikiAppreciationQueryPort appreciationQueryPort;

    public SetWikiAppreciationUseCase(
            WikiArticleQueryPort wikiArticleQueryPort,
            SetWikiAppreciationAttemptExecutor attemptExecutor,
            WikiAppreciationQueryPort appreciationQueryPort
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
        this.attemptExecutor = Objects.requireNonNull(
                attemptExecutor,
                "SetWikiAppreciationAttemptExecutor không được để trống."
        );
        this.appreciationQueryPort = Objects.requireNonNull(
                appreciationQueryPort,
                "WikiAppreciationQueryPort không được để trống."
        );
    }

    public SetWikiAppreciationResult execute(SetWikiAppreciationCommand command) {
        Objects.requireNonNull(command, "SetWikiAppreciationCommand không được để trống.");

        UUID articleId = command.wikiArticleId();

        // 1. Kiểm tra tính hợp lệ của bài viết Wiki (tồn tại, PUBLISHED, và thuộc loại CHARACTER / FACTION)
        WikiArticleEligibilitySnapshot article = wikiArticleQueryPort.findEligibilityById(articleId)
                .orElseThrow(() -> new WikiAppreciationTargetNotFoundException(articleId));

        if (!ArticleStatus.PUBLISHED.name().equalsIgnoreCase(article.status())) {
            throw new WikiAppreciationTargetNotFoundException(articleId);
        }

        if (!ArticleType.CHARACTER.name().equalsIgnoreCase(article.articleType())
                && !ArticleType.FACTION.name().equalsIgnoreCase(article.articleType())) {
            throw new WikiAppreciationTargetNotFoundException(articleId);
        }

        // 2. Thực thi ghi nhận với cơ chế bounded retry (tối đa 2 attempts: 1 normal + 1 fresh retry khi chạm duplicate race)
        AppreciationMutationAttemptResult attemptResult;
        try {
            attemptResult = attemptExecutor.executeAttempt(command);
        } catch (DuplicateWikiAppreciationException ex) {
            // Thử lại chính xác 1 lần trong transaction REQUIRES_NEW mới độc lập
            attemptResult = attemptExecutor.executeAttempt(command);
        }

        // 3. Truy vấn summary cộng đồng mới nhất sau khi transaction của attempt đã commit
        WikiAppreciationSummary summary = appreciationQueryPort.findSummaryByWikiArticleId(articleId);

        return new SetWikiAppreciationResult(
                articleId,
                command.value(),
                summary.average(),
                summary.count(),
                attemptResult.changed()
        );
    }
}
