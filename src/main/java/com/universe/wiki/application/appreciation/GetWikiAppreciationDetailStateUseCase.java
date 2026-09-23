package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case tra cứu và tổng hợp trạng thái đánh giá mức độ yêu thích cho trang chi tiết Wiki công khai.
 *
 * Quy tắc:
 * - Luôn truy vấn summary cộng đồng từ F3;
 * - Nếu người xem đã xác thực (viewerUserId != null): truy vấn đánh giá hiện hành của viewer và chuyển sang toStars();
 * - Nếu người xem ẩn danh (viewerUserId == null): không truy vấn per-user rating;
 * - Điểm trung bình và tổng số lượt đánh giá được giữ nguyên từ summary, không tính toán lại trong Java.
 */
@Service
public class GetWikiAppreciationDetailStateUseCase {

    private final WikiAppreciationQueryPort queryPort;
    private final WikiAppreciationRepositoryPort repositoryPort;

    public GetWikiAppreciationDetailStateUseCase(
            WikiAppreciationQueryPort queryPort,
            WikiAppreciationRepositoryPort repositoryPort
    ) {
        this.queryPort = Objects.requireNonNull(
                queryPort,
                "WikiAppreciationQueryPort không được để trống."
        );
        this.repositoryPort = Objects.requireNonNull(
                repositoryPort,
                "WikiAppreciationRepositoryPort không được để trống."
        );
    }

    public WikiAppreciationDetailState execute(UUID articleId, UUID viewerUserId) {
        Objects.requireNonNull(articleId, "ID bài viết Wiki không được để trống.");

        WikiAppreciationSummary summary = queryPort.findSummaryByWikiArticleId(articleId);

        BigDecimal viewerValue = null;
        if (viewerUserId != null) {
            viewerValue = repositoryPort.findByWikiArticleIdAndUserId(articleId, viewerUserId)
                    .map(rating -> rating.getScore().toStars())
                    .orElse(null);
        }

        return new WikiAppreciationDetailState(
                summary.average(),
                summary.count(),
                viewerValue
        );
    }
}
