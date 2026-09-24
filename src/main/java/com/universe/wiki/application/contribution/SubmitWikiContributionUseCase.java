package com.universe.wiki.application.contribution;

import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionSourceRepositoryPort;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionContextType;
import com.universe.wiki.domain.contribution.WikiContributionSource;
import com.universe.wiki.domain.contribution.WikiContributionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Use case xử lý tiếp nhận đóng góp bài viết Wiki từ độc giả đã xác thực.
 *
 * Quy tắc thực thi:
 * 1. Xác thực bài viết tồn tại và đang ở trạng thái PUBLISHED;
 * 2. Bảo toàn articleContentVersion do độc giả nhìn thấy (1 <= readerVersion <= currentVersion);
 * 3. Chụp nhanh thông tin bài viết (articleTypeSnapshot, articleTitleSnapshot, articleSlugSnapshot);
 * 4. Kiểm tra cú pháp, ngăn ngừa path traversal và phân loại 0..5 nguồn tham khảo (không gửi request mạng);
 * 5. Ngăn chặn trùng lặp URL quy chuẩn trong cùng một lượt đóng góp;
 * 6. Phòng ngừa đóng góp trùng lặp trong cửa sổ 60s (cùng user, article, contextType, contributionType,
 *    nội dung chuẩn hóa, văn bản chọn và tập nguồn quy chuẩn không phụ thuộc thứ tự);
 * 7. Lưu trữ nguyên tử (atomic) đóng góp và nguồn tham khảo trong cùng một transaction.
 */
@Service
public class SubmitWikiContributionUseCase {

    private static final int DEDUPLICATION_WINDOW_SECONDS = 60;
    private static final int MAX_CANDIDATE_SEARCH_LIMIT = 10;
    private static final int MAX_SOURCES_COUNT = 5;

    private final WikiArticleRepositoryPort articleRepository;
    private final WikiContributionRepositoryPort contributionRepository;
    private final WikiContributionSourceRepositoryPort sourceRepository;
    private final ClockPort clockPort;

    public SubmitWikiContributionUseCase(
            WikiArticleRepositoryPort articleRepository,
            WikiContributionRepositoryPort contributionRepository,
            WikiContributionSourceRepositoryPort sourceRepository,
            ClockPort clockPort
    ) {
        this.articleRepository = Objects.requireNonNull(articleRepository, "WikiArticleRepositoryPort không được để trống.");
        this.contributionRepository = Objects.requireNonNull(contributionRepository, "WikiContributionRepositoryPort không được để trống.");
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "WikiContributionSourceRepositoryPort không được để trống.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort không được để trống.");
    }

    @Transactional
    public SubmitWikiContributionResult execute(SubmitWikiContributionCommand command) {
        Objects.requireNonNull(command, "SubmitWikiContributionCommand không được để trống.");
        UUID articleId = Objects.requireNonNull(command.articleId(), "ID bài viết không được để trống.");
        UUID submittedByUserId = Objects.requireNonNull(command.submittedByUserId(), "ID người dùng không được để trống.");

        if (command.articleContentVersion() == null || command.articleContentVersion() < 1L) {
            throw new IllegalArgumentException("Phiên bản nội dung bài viết phải lớn hơn hoặc bằng 1.");
        }
        long readerContentVersion = command.articleContentVersion();

        if (command.contextType() == null || command.contextType().isBlank()) {
            throw new IllegalArgumentException("Loại ngữ cảnh đóng góp không được để trống.");
        }
        WikiContributionContextType contextType;
        try {
            contextType = WikiContributionContextType.valueOf(command.contextType().trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Loại ngữ cảnh đóng góp không hợp lệ: " + command.contextType());
        }

        if (command.contributionType() == null || command.contributionType().isBlank()) {
            throw new IllegalArgumentException("Loại đóng góp không được để trống.");
        }
        WikiContributionType contributionType;
        try {
            contributionType = WikiContributionType.valueOf(command.contributionType().trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Loại đóng góp không hợp lệ: " + command.contributionType());
        }

        if (command.message() == null || command.message().trim().isEmpty()) {
            throw new IllegalArgumentException("Nội dung đóng góp không được để trống.");
        }

        // 1. Kiểm tra bài viết tồn tại và đã xuất bản
        WikiArticle article = articleRepository.findById(articleId)
                .orElseThrow(() -> new PublishedWikiArticleNotFoundException(articleId));

        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new PublishedWikiArticleNotFoundException(articleId);
        }

        // 2. Kiểm tra phiên bản nội dung độc giả thấy so với phiên bản hiện tại
        long currentContentVersion = article.getContentVersion();
        if (readerContentVersion > currentContentVersion) {
            throw new IllegalArgumentException(
                    String.format("Phiên bản nội dung bài viết (%d) không được lớn hơn phiên bản hiện tại (%d).",
                            readerContentVersion, currentContentVersion)
            );
        }

        // 3. Trích xuất snapshot từ bài viết hiện tại
        String articleTypeSnapshot = article.getArticleType().name();
        String articleTitleSnapshot = article.getTitle();
        String articleSlugSnapshot = article.getSlug().value();

        // 4. Kiểm tra và phân loại các nguồn tham khảo (0..5)
        List<String> rawSources = command.sources() != null ? command.sources() : List.of();
        if (rawSources.size() > MAX_SOURCES_COUNT) {
            throw new IllegalArgumentException(
                    String.format("Mỗi đóng góp chỉ được chứa tối đa %d nguồn tham khảo.", MAX_SOURCES_COUNT)
            );
        }

        Instant now = clockPort.now();
        UUID contributionId = UUID.randomUUID();

        List<WikiContributionSource> sources = new ArrayList<>();
        Set<String> canonicalUrls = new HashSet<>();
        for (int i = 0; i < rawSources.size(); i++) {
            String rawUrl = rawSources.get(i);
            WikiContributionSource source = WikiContributionSource.create(
                    UUID.randomUUID(),
                    contributionId,
                    i,
                    rawUrl,
                    now
            );
            if (!canonicalUrls.add(source.getUrl())) {
                throw new IllegalArgumentException("Các nguồn tham khảo không được chứa URL trùng lặp: " + source.getUrl());
            }
            sources.add(source);
        }

        // 5. Khởi tạo đối tượng domain WikiContribution (thực hiện validation nội dung và trích dẫn)
        WikiContribution contribution;
        if (contextType == WikiContributionContextType.GENERAL) {
            if ((command.selectedText() != null && !command.selectedText().trim().isEmpty())
                    || (command.selectedPrefix() != null && !command.selectedPrefix().trim().isEmpty())
                    || (command.selectedSuffix() != null && !command.selectedSuffix().trim().isEmpty())
                    || (command.selectedHeadingAnchor() != null && !command.selectedHeadingAnchor().trim().isEmpty())) {
                throw new IllegalArgumentException("Đóng góp dạng tổng quan không được chứa thông tin trích dẫn.");
            }
            contribution = WikiContribution.createGeneral(
                    contributionId,
                    articleId,
                    articleTypeSnapshot,
                    articleTitleSnapshot,
                    articleSlugSnapshot,
                    readerContentVersion,
                    submittedByUserId,
                    contributionType,
                    command.message(),
                    now
            );
        } else if (contextType == WikiContributionContextType.TEXT_SELECTION) {
            contribution = WikiContribution.createTextSelection(
                    contributionId,
                    articleId,
                    articleTypeSnapshot,
                    articleTitleSnapshot,
                    articleSlugSnapshot,
                    readerContentVersion,
                    submittedByUserId,
                    contributionType,
                    command.message(),
                    command.selectedText(),
                    command.selectedPrefix(),
                    command.selectedSuffix(),
                    command.selectedHeadingAnchor(),
                    now
            );
        } else {
            throw new IllegalArgumentException("Loại ngữ cảnh đóng góp không hợp lệ: " + contextType);
        }

        // 6. Cơ chế phòng ngừa gửi trùng lặp (Best-effort duplicate guard trong 60 giây)
        Instant cutoff = now.minusSeconds(DEDUPLICATION_WINDOW_SECONDS);
        List<WikiContribution> recentCandidates = contributionRepository.findRecentCandidates(
                submittedByUserId,
                articleId,
                cutoff,
                MAX_CANDIDATE_SEARCH_LIMIT
        );

        for (WikiContribution candidate : recentCandidates) {
            if (isDuplicateSubmission(candidate, contribution, canonicalUrls)) {
                return new SubmitWikiContributionResult(
                        candidate.getId(),
                        candidate.getStatus().name(),
                        true,
                        "Đóng góp tương tự đã được gửi trước đó và đang chờ xử lý."
                );
            }
        }

        // 7. Lưu trữ nguyên tử đóng góp và danh sách nguồn tham khảo
        WikiContribution savedContribution = contributionRepository.save(contribution);
        if (!sources.isEmpty()) {
            sourceRepository.saveAll(sources);
        }

        return new SubmitWikiContributionResult(
                savedContribution.getId(),
                savedContribution.getStatus().name(),
                false,
                "Đóng góp của bạn đã được gửi thành công và đang chờ kiểm duyệt."
        );
    }

    private boolean isDuplicateSubmission(
            WikiContribution candidate,
            WikiContribution incoming,
            Set<String> incomingCanonicalUrls
    ) {
        if (candidate.getContextType() != incoming.getContextType()) {
            return false;
        }
        if (candidate.getContributionType() != incoming.getContributionType()) {
            return false;
        }
        if (!Objects.equals(candidate.getMessage(), incoming.getMessage())) {
            return false;
        }
        if (!Objects.equals(candidate.getSelectedText(), incoming.getSelectedText())) {
            return false;
        }

        List<WikiContributionSource> candidateSources = sourceRepository.findByContributionId(candidate.getId());
        Set<String> candidateUrls = candidateSources.stream()
                .map(WikiContributionSource::getUrl)
                .collect(Collectors.toSet());

        return candidateUrls.equals(incomingCanonicalUrls);
    }
}
