package com.universe.wiki.application.contribution.credit;

import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiContributionCreditNotFoundException;
import com.universe.wiki.application.ports.WikiContributionCreditRepositoryPort;
import com.universe.wiki.domain.credit.WikiContributionCredit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Use case thực hiện thu hồi công trạng người đóng góp (Revoke Contributor Credit).
 *
 * Tuân thủ nghiêm ngặt các bất biến:
 * 1. Chỉ Quản trị viên cấp cao (SUPER_ADMIN) mới có quyền thu hồi;
 * 2. Chỉ có thể thu hồi công trạng đang ở trạng thái ACTIVE;
 * 3. Lý do thu hồi (revocationReason) bắt buộc, độ dài từ 5 đến 1000 ký tự sau khi trim;
 * 4. Thu hồi là thao tác terminal trong H8, không hỗ trợ tái kích hoạt;
 * 5. Khóa lạc quan (optimistic locking) được quản lý ở tầng persistence của chính aggregate Credit.
 */
@Service
public class RevokeWikiContributionCreditUseCase {

    private final WikiContributionCreditRepositoryPort creditRepository;
    private final UserIdentityContract userIdentityContract;
    private final ClockPort clockPort;

    public RevokeWikiContributionCreditUseCase(
            WikiContributionCreditRepositoryPort creditRepository,
            UserIdentityContract userIdentityContract,
            ClockPort clockPort
    ) {
        this.creditRepository = Objects.requireNonNull(
                creditRepository,
                "WikiContributionCreditRepositoryPort không được để trống."
        );
        this.userIdentityContract = Objects.requireNonNull(
                userIdentityContract,
                "UserIdentityContract không được để trống."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort không được để trống."
        );
    }

    @Transactional
    public WikiContributionCredit execute(RevokeWikiContributionCreditCommand command) {
        Objects.requireNonNull(command, "RevokeWikiContributionCreditCommand không được để trống.");

        // 1. Load credit by contributionId
        WikiContributionCredit credit = creditRepository.findByContributionId(command.contributionId())
                .orElseThrow(() -> new WikiContributionCreditNotFoundException(command.contributionId()));

        // 2. Require ACTIVE
        if (!credit.isActive()) {
            throw new IllegalStateException("Chỉ có thể thu hồi công trạng đang ở trạng thái ACTIVE; công trạng hiện tại đã bị thu hồi.");
        }

        // 3. Resolve authenticated actor from Identity contract
        UserDTO actorUser = userIdentityContract.findById(command.actorId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng thực hiện."));
        if (!"ACTIVE".equalsIgnoreCase(actorUser.status())) {
            throw new IllegalStateException("Tài khoản người thực hiện không còn hoạt động.");
        }

        // 4. Require active SUPER_ADMIN
        if (!"SUPER_ADMIN".equalsIgnoreCase(actorUser.role())) {
            throw new IllegalStateException("Chỉ Quản trị viên cấp cao (SUPER_ADMIN) mới có quyền thu hồi công trạng.");
        }

        // 5. Validate revocation reason: trimmed, 5..1000
        if (command.revocationReason() == null) {
            throw new IllegalArgumentException("Lý do thu hồi không được để trống.");
        }
        String trimmedReason = command.revocationReason().trim();
        if (trimmedReason.length() < 5 || trimmedReason.length() > 1000) {
            throw new IllegalArgumentException("Lý do thu hồi phải từ 5 đến 1000 ký tự.");
        }

        // 6. Invoke domain revoke
        Instant now = clockPort.now();
        credit.revoke(command.actorId(), trimmedReason, now);

        // 7. Persist with optimistic locking on the CREDIT persistence entity
        return creditRepository.save(credit);
    }
}
