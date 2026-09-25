package com.universe.wiki.application.contribution.credit;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionAlreadyCreditedException;
import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.ports.WikiContributionCreditRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionResolutionOutcome;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case thực hiện ghi nhận công trạng người đóng góp (Grant Contributor Credit).
 *
 * Tuân thủ nghiêm ngặt các bất biến:
 * 1. RESOLVED != CREDITED: Chỉ cho phép ghi nhận khi đóng góp ở trạng thái RESOLVED;
 * 2. Kết quả xử lý đủ điều kiện: Chỉ APPLIED hoặc NO_CHANGE_NEEDED (từ chối DUPLICATE, REJECTED, NEW, REVIEWING, null);
 * 3. Phân quyền: Người giải quyết đóng góp (resolvedByUserId) hoặc Quản trị viên cấp cao (SUPER_ADMIN);
 * 4. Không cho phép Quản trị viên không liên quan (unrelated ADMIN) ghi nhận;
 * 5. NO_CHANGE_NEEDED bắt buộc có credit note hợp lệ (5..1000 ký tự); APPLIED tùy chọn;
 * 6. Tuyệt đối không sao chép resolutionNote tự động;
 * 7. Không được phép tái ghi nhận khi công trạng đã bị thu hồi (REVOKED là terminal trong H8);
 * 8. Ràng buộc UNIQUE(contribution_id) trên cơ sở dữ liệu là thẩm quyền tối hậu chống race condition.
 */
@Service
public class GrantWikiContributionCreditUseCase {

    private final WikiContributionRepositoryPort contributionRepository;
    private final WikiContributionCreditRepositoryPort creditRepository;
    private final UserIdentityContract userIdentityContract;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public GrantWikiContributionCreditUseCase(
            WikiContributionRepositoryPort contributionRepository,
            WikiContributionCreditRepositoryPort creditRepository,
            UserIdentityContract userIdentityContract,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.contributionRepository = Objects.requireNonNull(
                contributionRepository,
                "WikiContributionRepositoryPort không được để trống."
        );
        this.creditRepository = Objects.requireNonNull(
                creditRepository,
                "WikiContributionCreditRepositoryPort không được để trống."
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

    @Transactional
    public WikiContributionCredit execute(GrantWikiContributionCreditCommand command) {
        Objects.requireNonNull(command, "GrantWikiContributionCreditCommand không được để trống.");

        // 1. Load WikiContribution
        WikiContribution contribution = contributionRepository.findById(command.contributionId())
                .orElseThrow(() -> new WikiContributionNotFoundException(command.contributionId()));

        // 2. Require status == RESOLVED
        if (contribution.getStatus() != WikiContributionStatus.RESOLVED) {
            throw new IllegalStateException(
                    String.format("Chỉ có thể ghi nhận công trạng cho đóng góp đã được giải quyết (RESOLVED), trạng thái hiện tại: %s.", contribution.getStatus())
            );
        }

        // 3. Require resolutionOutcome: APPLIED or NO_CHANGE_NEEDED
        // 4. Reject null outcome
        // 5. Reject DUPLICATE
        WikiContributionResolutionOutcome outcome = contribution.getResolutionOutcome();
        if (outcome == null) {
            throw new IllegalStateException("Đóng góp chưa có kết quả xử lý hợp lệ (outcome == null), không thể ghi nhận công trạng.");
        }
        if (outcome != WikiContributionResolutionOutcome.APPLIED && outcome != WikiContributionResolutionOutcome.NO_CHANGE_NEEDED) {
            throw new IllegalStateException(
                    String.format("Kết quả xử lý '%s' không đủ điều kiện ghi nhận công trạng. Chỉ áp dụng cho APPLIED hoặc NO_CHANGE_NEEDED.", outcome)
            );
        }

        // 6. Resolve authenticated actor identity through existing contract
        UserDTO actorUser = userIdentityContract.findById(command.actorId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng thực hiện."));
        if (!"ACTIVE".equalsIgnoreCase(actorUser.status())) {
            throw new IllegalStateException("Tài khoản người thực hiện không còn hoạt động.");
        }

        // 7. Actor must have an administrative role (ADMIN or SUPER_ADMIN)
        boolean isAdmin = "ADMIN".equalsIgnoreCase(actorUser.role());
        boolean isSuperAdmin = "SUPER_ADMIN".equalsIgnoreCase(actorUser.role());
        if (!isAdmin && !isSuperAdmin) {
            throw new IllegalStateException("Người thực hiện phải có vai trò Quản trị viên (ADMIN hoặc SUPER_ADMIN).");
        }

        // 8. Actor must be the original resolver OR an active SUPER_ADMIN
        boolean isResolvingAdmin = Objects.equals(contribution.getResolvedByUserId(), command.actorId());
        if (!isResolvingAdmin && !isSuperAdmin) {
            throw new IllegalStateException("Chỉ người đã giải quyết đóng góp hoặc Quản trị viên cấp cao (SUPER_ADMIN) mới có quyền ghi nhận công trạng.");
        }

        // 8. If outcome == NO_CHANGE_NEEDED: creditNote is required, trimmed, 5..1000
        // 9. If outcome == APPLIED: creditNote optional (trimmed, max 1000)
        String trimmedNote = null;
        if (command.creditNote() != null) {
            trimmedNote = command.creditNote().trim();
        }

        if (outcome == WikiContributionResolutionOutcome.NO_CHANGE_NEEDED) {
            if (trimmedNote == null || trimmedNote.isEmpty()) {
                throw new IllegalArgumentException("Ghi chú ghi nhận công trạng là bắt buộc đối với kết quả NO_CHANGE_NEEDED.");
            }
            if (trimmedNote.length() < 5 || trimmedNote.length() > 1000) {
                throw new IllegalArgumentException("Độ dài ghi chú ghi nhận công trạng phải từ 5 đến 1000 ký tự.");
            }
        } else {
            // APPLIED
            if (trimmedNote != null && !trimmedNote.isEmpty()) {
                if (trimmedNote.length() > 1000) {
                    throw new IllegalArgumentException("Độ dài ghi chú ghi nhận công trạng không được vượt quá 1000 ký tự.");
                }
            } else {
                trimmedNote = null;
            }
        }

        // 10. Check whether a credit record already exists
        Optional<WikiContributionCredit> existingOpt = creditRepository.findByContributionId(command.contributionId());
        if (existingOpt.isPresent()) {
            WikiContributionCredit existing = existingOpt.get();
            // 11. If ACTIVE exists: fail as already credited
            if (existing.isActive()) {
                throw new WikiContributionAlreadyCreditedException(command.contributionId());
            }
            // 12. If REVOKED exists: fail. H8 does not allow re-credit after revocation.
            if (existing.isRevoked()) {
                throw new IllegalStateException("Đóng góp này đã từng được ghi nhận công trạng nhưng đã bị thu hồi; không thể tái ghi nhận.");
            }
        }

        // 13. Create ACTIVE WikiContributionCredit
        Instant now = clockPort.now();
        WikiContributionCredit newCredit = WikiContributionCredit.createActive(
                idGeneratorPort.generate(),
                command.contributionId(),
                command.actorId(),
                now,
                trimmedNote
        );

        // 14. Persist with database uniqueness as final race authority
        // 15. Translate duplicate-key race into a stable application-level already-credited failure
        return creditRepository.save(newCredit);
    }
}
