package com.universe.wiki.application.appreciation;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleEligibilitySnapshot;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case thực hiện thiết lập hoặc cập nhật đánh giá mức độ yêu thích (Appreciation Rating)
 * của người dùng đối với một bài viết Wiki (Nhân vật hoặc Thế lực/Tông môn).
 *
 * Đảm bảo các bất biến nghiệp vụ:
 * 1. 1 User + 1 Wiki Article = tối đa 1 bản ghi đánh giá hiện hành;
 * 2. Bài viết Wiki bắt buộc phải tồn tại, đang ở trạng thái PUBLISHED và thuộc loại CHARACTER hoặc FACTION;
 * 3. Nếu gửi cùng giá trị điểm (same-value no-op): không gọi ClockPort, không lưu database, không đổi updatedAt, trả về changed=false;
 * 4. Nếu là đánh giá mới hoặc cập nhật khác điểm: gọi ClockPort đúng một lần, lưu bản ghi, truy vấn summary mới nhất, trả về changed=true;
 * 5. Giao dịch toàn vẹn (@Transactional), các thay đổi lưu trữ được flush để truy vấn summary quan sát chính xác.
 */
@Service
@Transactional
public class SetWikiAppreciationUseCase {

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final WikiAppreciationRepositoryPort appreciationRepositoryPort;
    private final WikiAppreciationQueryPort appreciationQueryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public SetWikiAppreciationUseCase(
            WikiArticleQueryPort wikiArticleQueryPort,
            WikiAppreciationRepositoryPort appreciationRepositoryPort,
            WikiAppreciationQueryPort appreciationQueryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
        this.appreciationRepositoryPort = Objects.requireNonNull(
                appreciationRepositoryPort,
                "WikiAppreciationRepositoryPort không được để trống."
        );
        this.appreciationQueryPort = Objects.requireNonNull(
                appreciationQueryPort,
                "WikiAppreciationQueryPort không được để trống."
        );
        this.idGeneratorPort = Objects.requireNonNull(
                idGeneratorPort,
                "IdGeneratorPort không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    public SetWikiAppreciationResult execute(SetWikiAppreciationCommand command) {
        Objects.requireNonNull(command, "SetWikiAppreciationCommand không được để trống.");

        UUID articleId = command.wikiArticleId();
        UUID userId = command.actorUserId();
        int requestedValue = command.value();

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

        // 2. Tra cứu đánh giá hiện hành của người dùng trên bài viết
        Optional<WikiAppreciationRating> currentRatingOpt =
                appreciationRepositoryPort.findByWikiArticleIdAndUserId(articleId, userId);

        // 3. Xử lý trường hợp gửi lại cùng giá trị điểm (same-value no-op)
        if (currentRatingOpt.isPresent() && currentRatingOpt.get().getValue() == requestedValue) {
            // Không gọi ClockPort, không lưu database, không tạo UUID mới
            WikiAppreciationSummary summary = appreciationQueryPort.findSummaryByWikiArticleId(articleId);
            return new SetWikiAppreciationResult(
                    articleId,
                    requestedValue,
                    summary.average(),
                    summary.count(),
                    false
            );
        }

        // 4. Với đánh giá lần đầu hoặc cập nhật khác điểm: gọi ClockPort chính xác một lần
        Instant now = clockPort.now();

        if (currentRatingOpt.isPresent()) {
            WikiAppreciationRating rating = currentRatingOpt.get();
            rating.updateValue(requestedValue, now);
            appreciationRepositoryPort.save(rating);
        } else {
            UUID ratingId = idGeneratorPort.generate();
            WikiAppreciationRating newRating = WikiAppreciationRating.create(
                    ratingId,
                    articleId,
                    userId,
                    requestedValue,
                    now
            );
            appreciationRepositoryPort.save(newRating);
        }

        // 5. Truy vấn summary cộng đồng mới nhất sau khi dữ liệu đã được persist
        WikiAppreciationSummary summary = appreciationQueryPort.findSummaryByWikiArticleId(articleId);

        return new SetWikiAppreciationResult(
                articleId,
                requestedValue,
                summary.average(),
                summary.count(),
                true
        );
    }
}
