package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter persistence thực thi WikiAppreciationQueryPort cho các truy vấn tổng hợp đánh giá.
 *
 * Đảm bảo:
 * 1. Tính toán COUNT(*) và AVG(value) trực tiếp trong SQL, không hydrate thực thể JPA;
 * 2. Giữ nguyên độ chính xác BigDecimal từ database, không chuyển đổi qua double/float;
 * 3. Trả về count=0, average=null đối với bài viết chưa có lượt đánh giá nào;
 * 4. Truy vấn hàng loạt (bulk) thực thi duy nhất 1 câu SQL (GROUP BY), điền sẵn các phần tử rỗng, không N+1;
 * 5. Tự động loại bỏ trùng lặp trong input và từ chối các phần tử null.
 */
@Component
@Transactional(readOnly = true)
public class WikiAppreciationQueryPersistenceAdapter implements WikiAppreciationQueryPort {

    private static final String SQL_SINGLE_SUMMARY = """
            SELECT wiki_article_id, COUNT(*) AS rating_count, AVG(value) AS rating_average
            FROM wiki_appreciation_ratings
            WHERE wiki_article_id = ?
            GROUP BY wiki_article_id
            """;

    private static final String SQL_BULK_SUMMARY = """
            SELECT wiki_article_id, COUNT(*) AS rating_count, AVG(value) AS rating_average
            FROM wiki_appreciation_ratings
            WHERE wiki_article_id IN (:articleIds)
            GROUP BY wiki_article_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public WikiAppreciationQueryPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "JdbcTemplate không được để trống."
        );
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Override
    public WikiAppreciationSummary findSummaryByWikiArticleId(UUID wikiArticleId) {
        Objects.requireNonNull(wikiArticleId, "ID bài viết Wiki không được để trống.");

        List<WikiAppreciationSummary> results = jdbcTemplate.query(
                SQL_SINGLE_SUMMARY,
                (rs, rowNum) -> {
                    long count = rs.getLong("rating_count");
                    BigDecimal average = rs.getBigDecimal("rating_average");
                    return new WikiAppreciationSummary(wikiArticleId, average, count);
                },
                wikiArticleId.toString()
        );

        if (results.isEmpty()) {
            return WikiAppreciationSummary.empty(wikiArticleId);
        }
        return results.get(0);
    }

    @Override
    public Map<UUID, WikiAppreciationSummary> findSummariesByWikiArticleIds(Collection<UUID> wikiArticleIds) {
        Objects.requireNonNull(wikiArticleIds, "Danh sách ID bài viết Wiki không được để trống.");

        if (wikiArticleIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<UUID> uniqueIds = new LinkedHashSet<>(wikiArticleIds.size());
        for (UUID id : wikiArticleIds) {
            if (id == null) {
                throw new NullPointerException("ID bài viết Wiki trong danh sách không được để trống.");
            }
            uniqueIds.add(id);
        }

        Map<UUID, WikiAppreciationSummary> resultMap = new LinkedHashMap<>(uniqueIds.size());
        for (UUID id : uniqueIds) {
            resultMap.put(id, WikiAppreciationSummary.empty(id));
        }

        List<String> stringIds = uniqueIds.stream()
                .map(UUID::toString)
                .toList();

        MapSqlParameterSource parameters = new MapSqlParameterSource("articleIds", stringIds);

        namedParameterJdbcTemplate.query(
                SQL_BULK_SUMMARY,
                parameters,
                rs -> {
                    UUID articleId = UUID.fromString(rs.getString("wiki_article_id"));
                    long count = rs.getLong("rating_count");
                    BigDecimal average = rs.getBigDecimal("rating_average");
                    resultMap.put(articleId, new WikiAppreciationSummary(articleId, average, count));
                }
        );

        return Collections.unmodifiableMap(resultMap);
    }
}
