package com.universe.wiki.application.article.query.search;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchItemDTO;
import com.universe.wiki.contracts.dto.search.WikiNavigationalSearchResultDTO;
import com.universe.wiki.contracts.interfaces.WikiNavigationalSearchContract;
import com.universe.wiki.contracts.path.ArticleTypePathMapper;
import com.universe.wiki.domain.article.ArticleType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case thực hiện tìm kiếm điều hướng (Navigational Search) Wiki.
 *
 * Chỉ tìm kiếm theo tiêu đề chính thức và danh xưng/biệt danh (aliases) của các bài viết PUBLISHED.
 * Thứ tự ưu tiên xếp hạng (Relevance Ranking):
 * 1. Tiêu đề chính thức khớp chính xác (exact title)
 * 2. Alias khớp chính xác (exact alias)
 * 3. Tiêu đề chính thức khớp tiền tố (title prefix)
 * 4. Alias khớp tiền tố (alias prefix)
 * 5. Tiêu đề chính thức khớp một phần (title contains)
 * 6. Alias khớp một phần (alias contains)
 *
 * Tiêu chí sắp xếp ổn định (Deterministic Tie-Breaker):
 * - Relevance Rank ASC (1..6)
 * - Article Type ASC
 * - Article ID ASC
 */
@Service
public class WikiNavigationalSearchUseCase implements WikiNavigationalSearchContract {

    private static final Comparator<CandidateMatch> DETERMINISTIC_COMPARATOR = Comparator
            .comparingInt(CandidateMatch::rank)
            .thenComparing(c -> c.articleType().name())
            .thenComparing(c -> c.id().toString());

    private final WikiArticleQueryPort wikiArticleQueryPort;
    private final ArticleTypePathMapper articleTypePathMapper;

    public WikiNavigationalSearchUseCase(
            WikiArticleQueryPort wikiArticleQueryPort,
            ArticleTypePathMapper articleTypePathMapper
    ) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
        this.articleTypePathMapper = Objects.requireNonNull(
                articleTypePathMapper,
                "ArticleTypePathMapper không được để trống."
        );
    }

    @Override
    @Transactional(readOnly = true)
    public WikiNavigationalSearchResultDTO search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new WikiNavigationalSearchResultDTO("", List.of());
        }

        String cleanedQuery = WikiSearchNormalizer.cleanQuery(query);
        if (cleanedQuery.isEmpty()) {
            return new WikiNavigationalSearchResultDTO("", List.of());
        }

        String foldedQuery = WikiSearchNormalizer.fold(cleanedQuery);
        if (foldedQuery.isEmpty()) {
            return new WikiNavigationalSearchResultDTO(cleanedQuery, List.of());
        }

        int boundedLimit = boundLimit(limit);
        String escapedFoldedQuery = WikiSearchNormalizer.escapeLike(foldedQuery);

        // 1. Truy vấn đúng số lượng ứng viên tối đa theo tiêu đề và alias
        // (cả hai câu truy vấn DB đều sắp xếp đúng thứ bậc khớp và tiêu chí tất định trước khi giới hạn)
        List<WikiArticleListItemDTO> titleCandidates =
                wikiArticleQueryPort.findPublishedTitleSearchCandidates(foldedQuery, escapedFoldedQuery, boundedLimit);

        List<WikiArticleAliasSearchMatchDTO> aliasCandidates =
                wikiArticleQueryPort.findPublishedAliasSearchCandidates(foldedQuery, escapedFoldedQuery, boundedLimit);

        // 2. Đánh giá xếp hạng và loại trừ trùng lặp cho từng bài viết
        Map<UUID, CandidateMatch> deduplicatedCandidates = new HashMap<>();

        // Đánh giá Title matches (Rank 1, Rank 3, Rank 5)
        for (WikiArticleListItemDTO item : titleCandidates) {
            String foldedTitle = WikiSearchNormalizer.fold(item.title());
            int rank;
            if (foldedTitle.equals(foldedQuery)) {
                rank = 1;
            } else if (foldedTitle.startsWith(foldedQuery)) {
                rank = 3;
            } else if (foldedTitle.contains(foldedQuery)) {
                rank = 5;
            } else {
                continue;
            }

            Optional<ArticleType> articleTypeOpt = parseArticleType(item.articleType());
            if (articleTypeOpt.isEmpty()) {
                continue;
            }
            ArticleType articleType = articleTypeOpt.get();

            CandidateMatch candidate = new CandidateMatch(
                    item.id(),
                    articleType,
                    item.title(),
                    item.slug(),
                    rank,
                    null
            );
            deduplicatedCandidates.merge(item.id(), candidate, this::mergeCandidateMatches);
        }

        // Đánh giá Alias matches (Rank 2, Rank 4, Rank 6)
        for (WikiArticleAliasSearchMatchDTO aliasItem : aliasCandidates) {
            String foldedAlias = WikiSearchNormalizer.fold(aliasItem.alias());
            int rank;
            if (foldedAlias.equals(foldedQuery)) {
                rank = 2;
            } else if (foldedAlias.startsWith(foldedQuery)) {
                rank = 4;
            } else if (foldedAlias.contains(foldedQuery)) {
                rank = 6;
            } else {
                continue;
            }

            CandidateMatch candidate = new CandidateMatch(
                    aliasItem.articleId(),
                    aliasItem.articleType(),
                    aliasItem.title(),
                    aliasItem.slug(),
                    rank,
                    aliasItem.alias()
            );
            deduplicatedCandidates.merge(aliasItem.articleId(), candidate, this::mergeCandidateMatches);
        }

        // 3. Sắp xếp tất cả ứng viên theo thứ tự hoàn toàn tất định và giới hạn số lượng kết quả
        List<WikiNavigationalSearchItemDTO> items = deduplicatedCandidates.values().stream()
                .sorted(DETERMINISTIC_COMPARATOR)
                .limit(boundedLimit)
                .map(this::toSearchItemDTO)
                .toList();

        return new WikiNavigationalSearchResultDTO(cleanedQuery, items);
    }

    private CandidateMatch mergeCandidateMatches(CandidateMatch existing, CandidateMatch replacement) {
        if (replacement.rank() < existing.rank()) {
            return replacement;
        }
        if (existing.rank() < replacement.rank()) {
            return existing;
        }
        // Khi cùng rank: chọn alias có thứ tự tất định
        if (existing.matchedAlias() != null && replacement.matchedAlias() != null) {
            int aliasComp = existing.matchedAlias().compareTo(replacement.matchedAlias());
            return aliasComp <= 0 ? existing : replacement;
        }
        return existing;
    }

    private WikiNavigationalSearchItemDTO toSearchItemDTO(CandidateMatch match) {
        String typePath = articleTypePathMapper.toPath(match.articleType());
        String canonicalUrl = "/wiki/" + typePath + "/" + match.slug();
        return new WikiNavigationalSearchItemDTO(
                match.id(),
                match.articleType(),
                match.title(),
                match.slug(),
                canonicalUrl,
                match.matchedAlias()
        );
    }

    private Optional<ArticleType> parseArticleType(String articleTypeString) {
        if (articleTypeString == null || articleTypeString.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ArticleType.valueOf(articleTypeString.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private int boundLimit(int limit) {
        if (limit <= 0) {
            return WikiNavigationalSearchContract.DEFAULT_LIMIT;
        }
        return Math.min(limit, WikiNavigationalSearchContract.MAX_LIMIT);
    }

    private record CandidateMatch(
            UUID id,
            ArticleType articleType,
            String title,
            String slug,
            int rank,
            String matchedAlias
    ) {
    }
}
