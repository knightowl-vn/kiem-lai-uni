package com.universe.wiki.application.appreciation;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional worker thực thi một lượt ghi nhận đánh giá (Appreciation Rating)
 * trong một transaction độc lập (REQUIRES_NEW).
 *
 * <p>Quy tắc bất biến:
 * <ul>
 *   <li>Tra cứu bản ghi hiện hành của người dùng trên bài viết Wiki;</li>
 *   <li>Nếu gửi cùng giá trị điểm (same-value no-op qua WikiAppreciationScore equality): KHÔNG gọi ClockPort,
 *       KHÔNG gọi IdGeneratorPort, KHÔNG gọi save, giữ nguyên updatedAt, trả về changed=false;</li>
 *   <li>Nếu là cập nhật điểm khác: gọi ClockPort một lần, cập nhật aggregate score, lưu bản ghi, trả về changed=true;</li>
 *   <li>Nếu là đánh giá mới: gọi ClockPort một lần, gọi IdGeneratorPort một lần, tạo aggregate, lưu bản ghi, trả về changed=true;</li>
 *   <li>Thực thi trong transaction độc lập (REQUIRES_NEW) để khi xảy ra xung đột unique constraint,
 *       transaction này được rollback sạch sẽ mà không làm hỏng Hibernate session hay gán cờ rollback-only lên caller.</li>
 * </ul>
 */
@Component
public class SetWikiAppreciationAttemptExecutor {

    private final WikiAppreciationRepositoryPort appreciationRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public SetWikiAppreciationAttemptExecutor(
            WikiAppreciationRepositoryPort appreciationRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.appreciationRepositoryPort = Objects.requireNonNull(
                appreciationRepositoryPort,
                "WikiAppreciationRepositoryPort không được để trống."
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppreciationMutationAttemptResult executeAttempt(SetWikiAppreciationCommand command) {
        Objects.requireNonNull(command, "SetWikiAppreciationCommand không được để trống.");

        UUID articleId = command.wikiArticleId();
        UUID userId = command.actorUserId();
        WikiAppreciationScore requestedScore = command.score();

        Optional<WikiAppreciationRating> currentRatingOpt =
                appreciationRepositoryPort.findByWikiArticleIdAndUserId(articleId, userId);

        if (currentRatingOpt.isPresent() && Objects.equals(currentRatingOpt.get().getScore(), requestedScore)) {
            return new AppreciationMutationAttemptResult(false);
        }

        Instant now = clockPort.now();

        if (currentRatingOpt.isPresent()) {
            WikiAppreciationRating rating = currentRatingOpt.get();
            rating.updateScore(requestedScore, now);
            appreciationRepositoryPort.save(rating);
        } else {
            UUID ratingId = idGeneratorPort.generate();
            WikiAppreciationRating newRating = WikiAppreciationRating.create(
                    ratingId,
                    articleId,
                    userId,
                    requestedScore,
                    now
            );
            appreciationRepositoryPort.save(newRating);
        }

        return new AppreciationMutationAttemptResult(true);
    }
}
