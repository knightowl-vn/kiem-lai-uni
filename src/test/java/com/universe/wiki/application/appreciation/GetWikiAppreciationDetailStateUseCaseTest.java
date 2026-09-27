package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.contracts.dto.appreciation.WikiAppreciationDetailState;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetWikiAppreciationDetailStateUseCase Unit Tests")
class GetWikiAppreciationDetailStateUseCaseTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private WikiAppreciationQueryPort queryPort;

    @Mock
    private WikiAppreciationRepositoryPort repositoryPort;

    private GetWikiAppreciationDetailStateUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetWikiAppreciationDetailStateUseCase(queryPort, repositoryPort);
    }

    @Test
    @DisplayName("Ném NullPointerException khi queryPort hoặc repositoryPort là null trong constructor")
    void shouldThrowWhenDependenciesAreNull() {
        assertThatThrownBy(() -> new GetWikiAppreciationDetailStateUseCase(null, repositoryPort))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiAppreciationQueryPort");

        assertThatThrownBy(() -> new GetWikiAppreciationDetailStateUseCase(queryPort, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("WikiAppreciationRepositoryPort");
    }

    @Test
    @DisplayName("Ném NullPointerException khi articleId là null")
    void shouldThrowWhenArticleIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, USER_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki");

        verifyNoInteractions(queryPort);
        verifyNoInteractions(repositoryPort);
    }

    @Test
    @DisplayName("Người xem ẩn danh (viewerUserId == null): truy vấn summary cộng đồng, KHÔNG gọi repository tra cứu per-user rating")
    void shouldReturnSummaryOnlyWhenViewerIsAnonymous() {
        BigDecimal average = new BigDecimal("4.67");
        WikiAppreciationSummary summary = new WikiAppreciationSummary(ARTICLE_ID, average, 12L);
        when(queryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(summary);

        WikiAppreciationDetailState state = useCase.execute(ARTICLE_ID, null);

        assertThat(state.average()).isEqualTo(average);
        assertThat(state.count()).isEqualTo(12L);
        assertThat(state.viewerValue()).isNull();

        verify(queryPort).findSummaryByWikiArticleId(ARTICLE_ID);
        verify(repositoryPort, never()).findByWikiArticleIdAndUserId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Người xem đã xác thực nhưng chưa đánh giá: trả về viewerValue = null và summary cộng đồng")
    void shouldReturnSummaryAndNullViewerValueWhenAuthenticatedViewerHasNotRated() {
        BigDecimal average = new BigDecimal("4.50");
        WikiAppreciationSummary summary = new WikiAppreciationSummary(ARTICLE_ID, average, 8L);
        when(queryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(summary);
        when(repositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());

        WikiAppreciationDetailState state = useCase.execute(ARTICLE_ID, USER_ID);

        assertThat(state.average()).isEqualTo(average);
        assertThat(state.count()).isEqualTo(8L);
        assertThat(state.viewerValue()).isNull();

        verify(queryPort).findSummaryByWikiArticleId(ARTICLE_ID);
        verify(repositoryPort).findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID);
    }

    @Test
    @DisplayName("Người xem đã xác thực và đã đánh giá: trả về đúng viewerValue dạng BigDecimal từ bản ghi rating")
    void shouldReturnSummaryAndCorrectViewerValueWhenAuthenticatedViewerHasRated() {
        BigDecimal average = new BigDecimal("4.80");
        WikiAppreciationSummary summary = new WikiAppreciationSummary(ARTICLE_ID, average, 15L);
        when(queryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(summary);

        WikiAppreciationScore score = WikiAppreciationScore.fromStars(new BigDecimal("4.5"));
        WikiAppreciationRating rating = WikiAppreciationRating.create(
                UUID.randomUUID(),
                ARTICLE_ID,
                USER_ID,
                score,
                Instant.now()
        );
        when(repositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.of(rating));

        WikiAppreciationDetailState state = useCase.execute(ARTICLE_ID, USER_ID);

        assertThat(state.average()).isEqualTo(average);
        assertThat(state.count()).isEqualTo(15L);
        assertThat(state.viewerValue()).isEqualByComparingTo(new BigDecimal("4.5"));

        verify(queryPort).findSummaryByWikiArticleId(ARTICLE_ID);
        verify(repositoryPort).findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID);
    }

    @Test
    @DisplayName("Bài viết chưa có đánh giá nào (count=0, average=null): bảo toàn trạng thái rỗng chuẩn")
    void shouldPreserveEmptySummaryState() {
        WikiAppreciationSummary summary = WikiAppreciationSummary.empty(ARTICLE_ID);
        when(queryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(summary);
        when(repositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());

        WikiAppreciationDetailState state = useCase.execute(ARTICLE_ID, USER_ID);

        assertThat(state.average()).isNull();
        assertThat(state.count()).isEqualTo(0L);
        assertThat(state.viewerValue()).isNull();
        assertThat(state.displayAverage()).isNull();
    }
}
