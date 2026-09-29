package com.universe.novel.application.locator;

import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort;
import com.universe.novel.application.ports.PublishedChapterLocatorQueryPort.PublishedChapterLocatorRecord;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case orchestrating the navigational search for published novel chapters.
 * Enforces fail-closed publication filtering, database-level and application-level deterministic ranking,
 * deduplication, and limit constraints.
 */
@Service
public class LocatePublishedChaptersUseCase {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 20;

    private final PublishedChapterLocatorQueryPort chapterLocatorQueryPort;

    public LocatePublishedChaptersUseCase(PublishedChapterLocatorQueryPort chapterLocatorQueryPort) {
        this.chapterLocatorQueryPort = Objects.requireNonNull(
                chapterLocatorQueryPort,
                "PublishedChapterLocatorQueryPort must not be null"
        );
    }

    /**
     * Executes chapter locator search.
     *
     * @param rawQuery user input search query
     * @param limit maximum results to return (clamped between 1 and 20)
     * @return locator result DTO
     */
    public NovelChapterLocatorResultDTO locate(String rawQuery, int limit) {
        String normalizedQuery = normalizeQuery(rawQuery);
        if (normalizedQuery.isEmpty()) {
            return new NovelChapterLocatorResultDTO("", null, List.of());
        }

        int effectiveLimit = clampLimit(limit);
        Optional<Integer> matchedChapterNumber = ChapterNumberQueryParser.parseChapterNumber(normalizedQuery);

        Map<UUID, RankedCandidate> candidates = new LinkedHashMap<>();

        // 1. Exact chapter number lookup (Rank 0)
        if (matchedChapterNumber.isPresent()) {
            Optional<PublishedChapterLocatorRecord> byNumber =
                    chapterLocatorQueryPort.findPublishedByChapterNumber(matchedChapterNumber.get());
            byNumber.ifPresent(record -> candidates.put(record.id(), new RankedCandidate(record, 0)));
        }

        // 2. Title matching lookup (Ranks 1, 2, 3) with bounded candidate limit
        // 2a. Unescaped query with đ/Đ folded to d (for exact SQL equality matching in CASE 1)
        String exactFoldedKeyword = ChapterTitleSearchNormalizer.foldD(normalizedQuery);
        // 2b. Escaped query with đ/Đ folded to d (for SQL LIKE matching in WHERE and CASE 2)
        String likeFoldedKeyword = ChapterTitleSearchNormalizer.escapeLikeWildcards(exactFoldedKeyword);

        List<PublishedChapterLocatorRecord> titleMatches =
                chapterLocatorQueryPort.findPublishedByTitleKeyword(
                        exactFoldedKeyword,
                        likeFoldedKeyword,
                        effectiveLimit
                );

        for (PublishedChapterLocatorRecord record : titleMatches) {
            int rank = ChapterTitleSearchNormalizer.determineTitleRank(record.title(), normalizedQuery);
            RankedCandidate existing = candidates.get(record.id());
            if (existing == null || rank < existing.rank()) {
                candidates.put(record.id(), new RankedCandidate(record, rank));
            }
        }

        // 3. Deterministic sorting: rank ASC, chapterNumber ASC
        List<NovelChapterLocatorItemDTO> items = candidates.values().stream()
                .sorted(Comparator.comparingInt(RankedCandidate::rank)
                        .thenComparingInt(c -> c.record().chapterNumber()))
                .limit(effectiveLimit)
                .map(this::toItemDTO)
                .toList();

        return new NovelChapterLocatorResultDTO(
                normalizedQuery,
                matchedChapterNumber.orElse(null),
                items
        );
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim().replaceAll("\\s+", " ");
        if (normalized.length() > MAX_QUERY_LENGTH) {
            normalized = normalized.substring(0, MAX_QUERY_LENGTH).trim();
        }
        return normalized;
    }

    private int clampLimit(int limit) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            return DEFAULT_LIMIT;
        }
        return limit;
    }

    private NovelChapterLocatorItemDTO toItemDTO(RankedCandidate candidate) {
        PublishedChapterLocatorRecord record = candidate.record();
        return new NovelChapterLocatorItemDTO(
                record.id(),
                record.chapterNumber(),
                record.title(),
                record.slug(),
                record.volumeSortOrder(),
                record.volumeTitle()
        );
    }

    private record RankedCandidate(
            PublishedChapterLocatorRecord record,
            int rank
    ) {
    }
}
