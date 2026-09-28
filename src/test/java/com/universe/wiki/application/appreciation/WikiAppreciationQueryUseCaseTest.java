package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetWikiAppreciationSummaryUseCase & GetWikiAppreciationSummariesUseCase Tests")
class WikiAppreciationQueryUseCaseTest {

    @Mock
    private WikiAppreciationQueryPort queryPort;

    @InjectMocks
    private GetWikiAppreciationSummaryUseCase getSingleUseCase;

    @InjectMocks
    private GetWikiAppreciationSummariesUseCase getBulkUseCase;

    private static final UUID ARTICLE_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARTICLE_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("Single query: ủy quyền truy vấn sang queryPort và trả về summary")
    void shouldDelegateSingleQueryToPort() {
        WikiAppreciationSummary expected = new WikiAppreciationSummary(ARTICLE_1, new BigDecimal("4.5"), 10L);
        when(queryPort.findSummaryByWikiArticleId(ARTICLE_1)).thenReturn(expected);

        WikiAppreciationSummary actual = getSingleUseCase.execute(ARTICLE_1);

        assertThat(actual).isSameAs(expected);
        verify(queryPort).findSummaryByWikiArticleId(ARTICLE_1);
    }

    @Test
    @DisplayName("Single query: từ chối ID null")
    void shouldRejectNullIdInSingleQuery() {
        assertThatThrownBy(() -> getSingleUseCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");
    }

    @Test
    @DisplayName("Bulk query: ủy quyền truy vấn sang queryPort và trả về Map kết quả")
    void shouldDelegateBulkQueryToPort() {
        List<UUID> ids = List.of(ARTICLE_1, ARTICLE_2);
        Map<UUID, WikiAppreciationSummary> expected = Map.of(
                ARTICLE_1, new WikiAppreciationSummary(ARTICLE_1, new BigDecimal("4.5"), 10L),
                ARTICLE_2, WikiAppreciationSummary.empty(ARTICLE_2)
        );
        when(queryPort.findSummariesByWikiArticleIds(ids)).thenReturn(expected);

        Map<UUID, WikiAppreciationSummary> actual = getBulkUseCase.execute(ids);

        assertThat(actual).isSameAs(expected);
        verify(queryPort).findSummariesByWikiArticleIds(ids);
    }

    @Test
    @DisplayName("Bulk query: từ chối collection null")
    void shouldRejectNullCollectionInBulkQuery() {
        assertThatThrownBy(() -> getBulkUseCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Danh sách ID bài viết Wiki không được để trống.");
    }

    @Test
    @DisplayName("Contract findSummaries: chuyển đổi kết quả sang DTO bất biến WikiAppreciationSummaryDTO")
    void shouldMapDomainSummariesToContractDtos() {
        List<UUID> ids = List.of(ARTICLE_1, ARTICLE_2);
        Map<UUID, WikiAppreciationSummary> domainResult = Map.of(
                ARTICLE_1, new WikiAppreciationSummary(ARTICLE_1, new BigDecimal("4.5"), 10L),
                ARTICLE_2, WikiAppreciationSummary.empty(ARTICLE_2)
        );
        when(queryPort.findSummariesByWikiArticleIds(ids)).thenReturn(domainResult);

        var dtoResult = getBulkUseCase.findSummaries(ids);

        assertThat(dtoResult).hasSize(2);
        assertThat(dtoResult.get(ARTICLE_1).average()).isEqualTo(new BigDecimal("4.5"));
        assertThat(dtoResult.get(ARTICLE_1).count()).isEqualTo(10L);
        assertThat(dtoResult.get(ARTICLE_1).displayAverage()).isEqualTo(new BigDecimal("4.5"));
        assertThat(dtoResult.get(ARTICLE_2).count()).isEqualTo(0L);
        assertThat(dtoResult.get(ARTICLE_2).average()).isNull();
        assertThat(dtoResult.get(ARTICLE_2).displayAverage()).isNull();
    }

    @Test
    @DisplayName("Contract findSummaries: trả về Map rỗng khi danh sách ID null hoặc rỗng mà không gọi queryPort")
    void shouldReturnEmptyMapForNullOrEmptyCollectionInContract() {
        assertThat(getBulkUseCase.findSummaries(null)).isEmpty();
        assertThat(getBulkUseCase.findSummaries(List.of())).isEmpty();
    }
}
