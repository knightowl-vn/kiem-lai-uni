package com.universe.wiki.application.contribution.workflow;

import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Use case thực thi các biến đổi trạng thái quy trình (workflow state transitions)
 * cho đóng góp bài viết Wiki từ phía quản trị viên.
 *
 * Các quy tắc bảo đảm:
 * 1. Actor identity và thời gian thực thi (now) luôn được cung cấp từ server (trusted);
 * 2. Khóa lạc quan (optimistic locking token: expectedVersion) được đối soát trước khi chuyển trạng thái;
 * 3. Chuyển trạng thái tuân thủ nghiêm ngặt State Machine của domain WikiContribution;
 * 4. Không bao giờ tự động retry khi gặp xung đột phiên bản;
 * 5. Khi RESOLVED: tự động chụp phiên bản nội dung bài viết hiện tại từ phía server nếu bài viết tồn tại;
 * 6. Không chỉnh sửa nội dung bài viết Wiki, không cấp điểm/credit cho độc giả (non-goals).
 */
@Service
public class AdminWikiContributionWorkflowUseCase {

    private final WikiContributionRepositoryPort contributionRepository;
    private final WikiArticleRepositoryPort articleRepository;
    private final ClockPort clockPort;

    public AdminWikiContributionWorkflowUseCase(
            WikiContributionRepositoryPort contributionRepository,
            WikiArticleRepositoryPort articleRepository,
            ClockPort clockPort
    ) {
        this.contributionRepository = Objects.requireNonNull(
                contributionRepository,
                "WikiContributionRepositoryPort không được để trống."
        );
        this.articleRepository = Objects.requireNonNull(
                articleRepository,
                "WikiArticleRepositoryPort không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    /**
     * Bắt đầu xem xét đóng góp (chuyển trạng thái từ NEW sang REVIEWING).
     */
    @Transactional
    public WikiContribution review(ReviewWikiContributionCommand command) {
        Objects.requireNonNull(command, "ReviewWikiContributionCommand không được để trống.");

        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        checkOptimisticLock(contribution, command.expectedVersion());

        Instant now = clockPort.now();
        contribution.markReviewing(now);

        return contributionRepository.save(contribution);
    }

    /**
     * Chấp thuận và giải quyết đóng góp (chuyển sang RESOLVED).
     */
    @Transactional
    public WikiContribution resolve(ResolveWikiContributionCommand command) {
        Objects.requireNonNull(command, "ResolveWikiContributionCommand không được để trống.");

        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        checkOptimisticLock(contribution, command.expectedVersion());

        Long currentArticleVersion = articleRepository.findById(contribution.getArticleId())
                .map(WikiArticle::getContentVersion)
                .orElse(null);

        Instant now = clockPort.now();
        contribution.resolve(command.actorId(), command.resolutionNote(), currentArticleVersion, now);

        return contributionRepository.save(contribution);
    }

    /**
     * Từ chối đóng góp (chuyển sang REJECTED).
     */
    @Transactional
    public WikiContribution reject(RejectWikiContributionCommand command) {
        Objects.requireNonNull(command, "RejectWikiContributionCommand không được để trống.");

        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        checkOptimisticLock(contribution, command.expectedVersion());

        Instant now = clockPort.now();
        contribution.reject(command.actorId(), command.resolutionNote(), now);

        return contributionRepository.save(contribution);
    }

    private void checkOptimisticLock(WikiContribution contribution, long expectedVersion) {
        if (contribution.getVersion() != expectedVersion) {
            throw new WikiContributionStaleMutationException(
                    contribution.getId(),
                    expectedVersion,
                    contribution.getVersion()
            );
        }
    }
}
