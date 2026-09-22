package com.universe.wiki.application.ports;

import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port truy vấn tổng hợp đánh giá mức độ yêu thích của cộng đồng (Appreciation Summary).
 */
public interface WikiAppreciationQueryPort {

    /**
     * Tra cứu tổng hợp đánh giá cho một bài viết Wiki đơn lẻ.
     * Khi bài viết chưa có lượt đánh giá nào, trả về summary với count=0, average=null.
     *
     * @param wikiArticleId ID bài viết Wiki (không được null)
     * @return WikiAppreciationSummary chứa count và average (null khi count=0)
     */
    WikiAppreciationSummary findSummaryByWikiArticleId(UUID wikiArticleId);

    /**
     * Tra cứu hàng loạt tổng hợp đánh giá cho tập hợp các bài viết Wiki (bulk query phục vụ Wiki cards).
     *
     * Quy tắc:
     * - Thực thi duy nhất 1 câu truy vấn SQL (GROUP BY wiki_article_id);
     * - Không N+1;
     * - Mọi ID bài viết được yêu cầu đều xuất hiện trong Map kết quả;
     * - Các bài viết chưa có đánh giá trong database được điền sẵn count=0, average=null;
     * - Input rỗng trả về Map rỗng (không thực thi truy vấn malformed);
     * - Input trùng lặp ID được tự động loại bỏ trùng lặp.
     *
     * @param wikiArticleIds tập hợp các ID bài viết Wiki (không chứa phần tử null)
     * @return Map ánh xạ từ wikiArticleId sang WikiAppreciationSummary tương ứng
     */
    Map<UUID, WikiAppreciationSummary> findSummariesByWikiArticleIds(Collection<UUID> wikiArticleIds);
}
