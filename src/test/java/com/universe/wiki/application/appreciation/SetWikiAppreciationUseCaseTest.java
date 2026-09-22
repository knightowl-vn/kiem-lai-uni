package com.universe.wiki.application.appreciation;

import com.universe.wiki.application.exceptions.DuplicateWikiAppreciationException;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleEligibilitySnapshot;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SetWikiAppreciationUseCase Unit Tests (MS-05F7)")
class SetWikiAppreciationUseCaseTest {

    private static final UUID ARTICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Mock
    private SetWikiAppreciationAttemptExecutor attemptExecutor;

    @Mock
    private WikiAppreciationQueryPort appreciationQueryPort;

    private SetWikiAppreciationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SetWikiAppreciationUseCase(
                wikiArticleQueryPort,
                attemptExecutor,
                appreciationQueryPort
        );
    }

    private WikiArticleEligibilitySnapshot createEligibilitySnapshot(ArticleType type, ArticleStatus status) {
        return new WikiArticleEligibilitySnapshot(
                ARTICLE_ID,
                type.name(),
                status.name()
        );
    }

    @Test
    @DisplayName("B. Normal successful attempt: executeAttempt gọi 1 lần, summary query 1 lần sau khi commit, trả về kết quả")
    void shouldExecuteAttemptAndReturnSummary() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 4);
        when(attemptExecutor.executeAttempt(command)).thenReturn(new AppreciationMutationAttemptResult(true));

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.0"), 1L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(result.value()).isEqualTo(4);
        assertThat(result.count()).isEqualTo(1L);
        assertThat(result.average()).isEqualByComparingTo(new BigDecimal("4.0"));
        assertThat(result.changed()).isTrue();

        verify(wikiArticleQueryPort, times(1)).findEligibilityById(ARTICLE_ID);
        verify(attemptExecutor, times(1)).executeAttempt(command);
        verify(appreciationQueryPort, times(1)).findSummaryByWikiArticleId(ARTICLE_ID);
    }

    @Test
    @DisplayName("B2. Normal successful attempt (same-value no-op): attemptExecutor trả về changed=false, summary query 1 lần")
    void shouldPreserveChangedFalseWhenSameValueNoOp() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.FACTION, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        when(attemptExecutor.executeAttempt(command)).thenReturn(new AppreciationMutationAttemptResult(false));

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.8"), 5L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.changed()).isFalse();
        assertThat(result.value()).isEqualTo(5);
        assertThat(result.count()).isEqualTo(5L);
        assertThat(result.average()).isEqualByComparingTo(new BigDecimal("4.8"));

        verify(attemptExecutor, times(1)).executeAttempt(command);
        verify(appreciationQueryPort, times(1)).findSummaryByWikiArticleId(ARTICLE_ID);
    }

    @Test
    @DisplayName("C. First attempt ném DuplicateWikiAppreciationException: attemptExecutor được gọi 2 lần (1 retry), summary query 1 lần sau retry thành công")
    void shouldRetryOnceWhenFirstAttemptEncountersDuplicateRace() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        // Attempt 1 ném DuplicateWikiAppreciationException; Attempt 2 thành công
        when(attemptExecutor.executeAttempt(command))
                .thenThrow(new DuplicateWikiAppreciationException(ARTICLE_ID, USER_ID, new RuntimeException("duplicate")))
                .thenReturn(new AppreciationMutationAttemptResult(true));

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("5.0"), 1L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.changed()).isTrue();
        assertThat(result.value()).isEqualTo(5);

        // Eligibility chỉ được check duy nhất 1 lần (không re-check khi retry)
        verify(wikiArticleQueryPort, times(1)).findEligibilityById(ARTICLE_ID);
        // AttemptExecutor gọi chính xác 2 lần
        verify(attemptExecutor, times(2)).executeAttempt(command);
        // Summary query được gọi duy nhất 1 lần sau khi retry thành công
        verify(appreciationQueryPort, times(1)).findSummaryByWikiArticleId(ARTICLE_ID);
    }

    @Test
    @DisplayName("D. Cả 2 attempts đều ném DuplicateWikiAppreciationException: ngoại lệ lan truyền ra ngoài, KHÔNG có attempt thứ 3, KHÔNG query summary")
    void shouldPropagateExceptionWhenRetryAlsoFailsWithDuplicate() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        when(attemptExecutor.executeAttempt(command))
                .thenThrow(new DuplicateWikiAppreciationException(ARTICLE_ID, USER_ID, new RuntimeException("dup1")))
                .thenThrow(new DuplicateWikiAppreciationException(ARTICLE_ID, USER_ID, new RuntimeException("dup2")));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(DuplicateWikiAppreciationException.class);

        verify(wikiArticleQueryPort, times(1)).findEligibilityById(ARTICLE_ID);
        verify(attemptExecutor, times(2)).executeAttempt(command);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("E. Ngoại lệ persistence/runtime không liên quan (unrelated exception): KHÔNG thử lại (no retry), lan truyền ngay lập tức")
    void shouldNotRetryOnUnrelatedException() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        when(attemptExecutor.executeAttempt(command))
                .thenThrow(new IllegalStateException("Lỗi cơ sở dữ liệu hoặc kết nối khác"));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lỗi cơ sở dữ liệu hoặc kết nối khác");

        verify(attemptExecutor, times(1)).executeAttempt(command);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("A1. Bài viết không tồn tại: ném WikiAppreciationTargetNotFoundException, attemptExecutor KHÔNG bao giờ được gọi")
    void shouldThrowWhenArticleDoesNotExist() {
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.empty());

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verifyNoInteractions(attemptExecutor);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("A2. Bài viết DRAFT CHARACTER: từ chối vì chưa xuất bản (WikiAppreciationTargetNotFoundException)")
    void shouldRejectDraftCharacter() {
        WikiArticleEligibilitySnapshot draftArticle = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.DRAFT);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(draftArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        verifyNoInteractions(attemptExecutor);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("A3. Bài viết ARCHIVED CHARACTER: từ chối vì đã lưu trữ (WikiAppreciationTargetNotFoundException)")
    void shouldRejectArchivedCharacter() {
        WikiArticleEligibilitySnapshot archivedArticle = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.ARCHIVED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(archivedArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        verifyNoInteractions(attemptExecutor);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("A4. Bài viết PUBLISHED ITEM: từ chối vì loại bài không đủ điều kiện")
    void shouldRejectPublishedItem() {
        WikiArticleEligibilitySnapshot itemArticle = createEligibilitySnapshot(ArticleType.ITEM, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(itemArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        verifyNoInteractions(attemptExecutor);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("A5. Bài viết PUBLISHED REALM: từ chối vì loại bài không đủ điều kiện")
    void shouldRejectPublishedRealm() {
        WikiArticleEligibilitySnapshot realmArticle = createEligibilitySnapshot(ArticleType.REALM, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(realmArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class);

        verifyNoInteractions(attemptExecutor);
        verifyNoInteractions(appreciationQueryPort);
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, -1, 0, 6, 10})
    @DisplayName("Giá trị đánh giá không hợp lệ: bị từ chối ngay tại Command")
    void shouldRejectInvalidRatingValue(int invalidValue) {
        assertThatThrownBy(() -> new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, invalidValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Giá trị đánh giá phải nằm trong khoảng từ 1 đến 5 sao");

        verifyNoInteractions(wikiArticleQueryPort);
        verifyNoInteractions(attemptExecutor);
        verifyNoInteractions(appreciationQueryPort);
    }

    @Test
    @DisplayName("Từ chối lệnh hoặc tham số null")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SetWikiAppreciationCommand không được để trống.");

        assertThatThrownBy(() -> new SetWikiAppreciationCommand(null, USER_ID, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki không được để trống.");

        assertThatThrownBy(() -> new SetWikiAppreciationCommand(ARTICLE_ID, null, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");
    }
}
