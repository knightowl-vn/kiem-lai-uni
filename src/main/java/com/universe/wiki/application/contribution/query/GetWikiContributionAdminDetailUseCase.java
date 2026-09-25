package com.universe.wiki.application.contribution.query;

import com.universe.wiki.application.exceptions.WikiContributionNotFoundException;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case truy vấn chi tiết đóng góp Wiki phục vụ giao diện quản trị kiểm duyệt.
 *
 * Tải toàn văn thông điệp, đầy đủ bằng chứng trích dẫn, danh sách nguồn tham khảo
 * được sắp xếp tăng dần theo sourceOrder (0..4) và lịch sử sự kiện kiểm toán quy trình.
 */
@Service
public class GetWikiContributionAdminDetailUseCase {

    private final WikiContributionRepositoryPort contributionRepository;
    private final WikiContributionSourceRepositoryPort sourceRepository;
    private final WikiContributionWorkflowEventRepositoryPort workflowEventRepository;

    public GetWikiContributionAdminDetailUseCase(
            WikiContributionRepositoryPort contributionRepository,
            WikiContributionSourceRepositoryPort sourceRepository,
            WikiContributionWorkflowEventRepositoryPort workflowEventRepository
    ) {
        this.contributionRepository = Objects.requireNonNull(
                contributionRepository,
                "WikiContributionRepositoryPort cannot be null"
        );
        this.sourceRepository = Objects.requireNonNull(
                sourceRepository,
                "WikiContributionSourceRepositoryPort cannot be null"
        );
        this.workflowEventRepository = Objects.requireNonNull(
                workflowEventRepository,
                "WikiContributionWorkflowEventRepositoryPort cannot be null"
        );
    }

    /**
     * Lấy chi tiết đóng góp bài viết Wiki theo ID.
     *
     * @param contributionId ID đóng góp
     * @return WikiContributionAdminDetail chứa trọn vẹn thông tin đóng góp, nguồn tham khảo và sự kiện kiểm toán
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
        List<WikiContributionWorkflowEvent> events = workflowEventRepository.findByContributionId(contributionId);

        return WikiContributionAdminDetail.from(contribution, sources, events);
    }
}
