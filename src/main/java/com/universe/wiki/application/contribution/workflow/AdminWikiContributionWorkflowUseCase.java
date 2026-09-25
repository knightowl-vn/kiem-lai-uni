package com.universe.wiki.application.contribution.workflow;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.exceptions.WikiContributionStaleMutationException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
import com.universe.wiki.domain.revision.WikiArticleRevision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case thực thi các biến đổi trạng thái quy trình (workflow state transitions)
 * cho đóng góp bài viết Wiki từ phía quản trị viên.
 *
 * Các quy tắc bảo đảm:
 * 1. Actor identity và thời gian thực thi (now) luôn được cung cấp từ server (trusted);
 * 2. Khóa lạc quan (optimistic locking token: expectedVersion) được đối soát trước khi chuyển trạng thái;
 * 3. Chuyển trạng thái tuân thủ nghiêm ngặt State Machine của domain WikiContribution;
 * 4. Không bao giờ tự động retry khi gặp xung đột phiên bản;
 * 5. Bắt đầu xem xét (review) ghi nhận thông tin người nhận và phiên bản bài viết hiện tại;
 * 6. Tiếp nhận (claim) cho phép gán người xử lý cho các đóng góp REVIEWING chưa có người phụ trách;
 * 7. Phân công lại (reassign) chỉ cho phép chuyển sang tài khoản Quản trị viên đang hoạt động;
 * 8. Giải quyết (resolve) chỉ thực hiện bởi người đang được phân công, yêu cầu kết quả xử lý rõ ràng.
 *    Nếu kết quả là APPLIED, bắt buộc phải có bản sửa bài viết đã liên kết;
 * 9. Từ chối (reject) chỉ thực hiện bởi người đang được phân công;
 * 10. Mọi thao tác thay đổi quy trình đều ghi nhận sự kiện kiểm toán bất biến (Workflow Event).
 */
@Service
public class AdminWikiContributionWorkflowUseCase {

    private final WikiContributionRepositoryPort contributionRepository;
    private final WikiArticleRepositoryPort articleRepository;
    private final WikiArticleRevisionRepositoryPort revisionRepository;
    private final WikiContributionWorkflowEventRepositoryPort workflowEventRepository;
    private final UserIdentityContract userIdentityContract;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public AdminWikiContributionWorkflowUseCase(
            WikiContributionRepositoryPort contributionRepository,
            WikiArticleRepositoryPort articleRepository,
            WikiArticleRevisionRepositoryPort revisionRepository,
            WikiContributionWorkflowEventRepositoryPort workflowEventRepository,
            UserIdentityContract userIdentityContract,
            IdGeneratorPort idGeneratorPort,
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
        this.revisionRepository = Objects.requireNonNull(
                revisionRepository,
                "WikiArticleRevisionRepositoryPort không được để trống."
        );
        this.workflowEventRepository = Objects.requireNonNull(
                workflowEventRepository,
                "WikiContributionWorkflowEventRepositoryPort không được để trống."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract không được để trống."
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

    /**
     * Bắt đầu xem xét đóng góp (chuyển trạng thái từ NEW sang REVIEWING và gán người phụ trách).
     */
    @Transactional
    public WikiContribution review(ReviewWikiContributionCommand command) {
        Objects.requireNonNull(command, "ReviewWikiContributionCommand không được để trống.");

        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        checkOptimisticLock(contribution, command.expectedVersion());

        Instant now = clockPort.now();
        Long currentArticleVersion = articleRepository.findById(contribution.getArticleId())
                .map(WikiArticle::getContentVersion)
                .orElse(null);

        contribution.startReview(command.actorId(), currentArticleVersion, now);
        WikiContribution saved = contributionRepository.save(contribution);

        WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.create(
                idGeneratorPort.generate(),
                saved.getId(),
                WikiContributionEventType.REVIEW_STARTED,
                command.actorId(),
                null,
                currentArticleVersion,
                null,
                null,
                now
        );
        workflowEventRepository.save(event);

        return saved;
    }

    /**
     * Tiếp nhận đóng góp REVIEWING chưa được phân công (legacy claim).
     */
    @Transactional
    public WikiContribution claim(ClaimWikiContributionCommand command) {
        Objects.requireNonNull(command, "ClaimWikiContributionCommand không được để trống.");

        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        checkOptimisticLock(contribution, command.expectedVersion());

        Instant now = clockPort.now();
        contribution.claim(command.actorId(), now);
        WikiContribution saved = contributionRepository.save(contribution);

        WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.create(
                idGeneratorPort.generate(),
                saved.getId(),
                WikiContributionEventType.CLAIMED,
                command.actorId(),
                null,
                null,
                null,
                null,
                now
        );
        workflowEventRepository.save(event);

        return saved;
    }

    /**
     * Phân công lại đóng góp đang xem xét cho quản trị viên khác (dành cho SUPER_ADMIN).
     */
    @Transactional
    public WikiContribution reassign(ReassignWikiContributionCommand command) {
        Objects.requireNonNull(command, "ReassignWikiContributionCommand không được để trống.");

        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        checkOptimisticLock(contribution, command.expectedVersion());

        UserDTO targetUser = userIdentityContract.findById(command.targetUserId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng được phân công."));
        if (!"ACTIVE".equalsIgnoreCase(targetUser.status())) {
            throw new IllegalArgumentException("Tài khoản người được phân công không còn hoạt động.");
        }
        if (!"ADMIN".equalsIgnoreCase(targetUser.role()) && !"SUPER_ADMIN".equalsIgnoreCase(targetUser.role())) {
            throw new IllegalArgumentException("Người được phân công phải có vai trò Quản trị viên (ADMIN hoặc SUPER_ADMIN).");
        }

        Instant now = clockPort.now();
        contribution.reassign(command.targetUserId(), now);
        WikiContribution saved = contributionRepository.save(contribution);

        WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.create(
                idGeneratorPort.generate(),
                saved.getId(),
                WikiContributionEventType.REASSIGNED,
                command.actorId(),
                command.targetUserId(),
                null,
                null,
                command.reason().trim(),
                now
        );
        workflowEventRepository.save(event);

        return saved;
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

        if (contribution.getAssignedToUserId() == null) {
            throw new IllegalStateException("Đóng góp chưa được phân công xử lý. Vui lòng tiếp nhận trước khi xử lý.");
        }
        if (!contribution.getAssignedToUserId().equals(command.actorId())) {
            throw new IllegalStateException("Chỉ người đang được phân công xử lý mới có quyền xử lý đóng góp này.");
        }

        Long resolvedArticleContentVersion;
        if (command.outcome() == WikiContributionResolutionOutcome.APPLIED) {
            Optional<WikiArticleRevision> linkedRevisionOpt = revisionRepository.findLatestBySourceContributionId(contribution.getId());
            if (linkedRevisionOpt.isEmpty()) {
                throw new IllegalStateException("Không thể hoàn tất với kết quả ĐÃ ÁP DỤNG khi chưa có bản sửa bài viết nào được liên kết với đóng góp này.");
            }
            WikiArticleRevision linkedRevision = linkedRevisionOpt.get();
            if (!linkedRevision.articleId().equals(contribution.getArticleId())) {
                throw new IllegalStateException("Bản sửa bài viết được liên kết không khớp với bài viết của đóng góp.");
            }
            long baseVersion = contribution.getReviewStartedArticleContentVersion() != null
                    ? contribution.getReviewStartedArticleContentVersion()
                    : contribution.getArticleContentVersion();
            if (linkedRevision.contentVersion() <= baseVersion) {
                throw new IllegalStateException(
                        String.format("Phiên bản nội dung bản sửa (%d) phải lớn hơn phiên bản bắt đầu xem xét (%d).",
                                linkedRevision.contentVersion(), baseVersion)
                );
            }
            resolvedArticleContentVersion = linkedRevision.contentVersion();
        } else {
            resolvedArticleContentVersion = articleRepository.findById(contribution.getArticleId())
                    .map(WikiArticle::getContentVersion)
                    .orElse(null);
        }

        Instant now = clockPort.now();
        contribution.resolve(command.actorId(), command.outcome(), command.resolutionNote(), resolvedArticleContentVersion, now);
        WikiContribution saved = contributionRepository.save(contribution);

        WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.create(
                idGeneratorPort.generate(),
                saved.getId(),
                WikiContributionEventType.RESOLVED,
                command.actorId(),
                null,
                resolvedArticleContentVersion,
                command.outcome(),
                command.resolutionNote().trim(),
                now
        );
        workflowEventRepository.save(event);

        return saved;
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

        if (contribution.getAssignedToUserId() == null) {
            throw new IllegalStateException("Đóng góp chưa được phân công xử lý. Vui lòng tiếp nhận trước khi xử lý.");
        }
        if (!contribution.getAssignedToUserId().equals(command.actorId())) {
            throw new IllegalStateException("Chỉ người đang được phân công xử lý mới có quyền xử lý đóng góp này.");
        }

        Instant now = clockPort.now();
        contribution.reject(command.actorId(), command.resolutionNote(), now);
        WikiContribution saved = contributionRepository.save(contribution);

        WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.create(
                idGeneratorPort.generate(),
                saved.getId(),
                WikiContributionEventType.REJECTED,
                command.actorId(),
                null,
                null,
                null,
                command.resolutionNote().trim(),
                now
        );
        workflowEventRepository.save(event);

        return saved;
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
