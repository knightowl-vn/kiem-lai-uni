package com.universe.wiki.application.contribution.query;

import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case truy vấn chi tiết đóng góp Wiki phục vụ giao diện quản trị kiểm duyệt.
 *
 * Tải toàn văn thông điệp, đầy đủ bằng chứng trích dẫn và danh sách nguồn tham khảo
 * được sắp xếp tăng dần theo sourceOrder (0..4).
 */
@Service
public class GetWikiContributionAdminDetailUseCase {

    private final WikiContributionRepositoryPort contributionRepository;
    private final WikiContributionSourceRepositoryPort sourceRepository;

    public GetWikiContributionAdminDetailUseCase(
            WikiContributionRepositoryPort contributionRepository,
            WikiContributionSourceRepositoryPort sourceRepository
    ) {
        this.contributionRepository = Objects.requireNonNull(
                contributionRepository,
                "WikiContributionRepositoryPort cannot be null"
        );
        this.sourceRepository = Objects.requireNonNull(
                sourceRepository,
                "WikiContributionSourceRepositoryPort cannot be null"
        );
    }

    /**
     * Lấy chi tiết đóng góp bài viết Wiki theo ID.
     *
     * @param contributionId ID đóng góp
     * @return WikiContributionAdminDetail chứa trọn vẹn thông tin đóng góp và nguồn tham khảo
     * @throws WikiContributionNotFoundException nếu không tìm thấy đóng góp
     */
    @Transactional(readOnly = true)
    public WikiContributionAdminDetail execute(UUID contributionId) {
        if (contributionId == null) {
            throw new WikiContributionNotFoundException(null);
        }

        WikiContribution contribution = contributionRepository.findById(contributionId)
                .orElseThrow(() -> new WikiContributionNotFoundException(contributionId));

        List<WikiContributionSource> sources = sourceRepository.findByContributionId(contributionId);

        return WikiContributionAdminDetail.from(contribution, sources);
    }
}
