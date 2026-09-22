package com.universe.wiki.application.appreciation;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiAppreciationTargetNotFoundException;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleEligibilitySnapshot;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SetWikiAppreciationUseCase Unit Tests (MS-05F4)")
class SetWikiAppreciationUseCaseTest {

    private static final UUID ARTICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GENERATED_RATING_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Instant FROZEN_NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Mock
    private WikiAppreciationRepositoryPort appreciationRepositoryPort;

    @Mock
    private WikiAppreciationQueryPort appreciationQueryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private SetWikiAppreciationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SetWikiAppreciationUseCase(
                wikiArticleQueryPort,
                appreciationRepositoryPort,
                appreciationQueryPort,
                idGeneratorPort,
                clockPort
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
    @DisplayName("A. Đánh giá lần đầu: bài viết PUBLISHED CHARACTER -> tạo mới rating, gọi clock/id một lần, changed=true")
    void shouldCreateFirstRatingForPublishedCharacter() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(FROZEN_NOW);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_RATING_ID);

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.0"), 1L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 4);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.wikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(result.value()).isEqualTo(4);
        assertThat(result.count()).isEqualTo(1L);
        assertThat(result.average()).isEqualByComparingTo(new BigDecimal("4.0"));
        assertThat(result.changed()).isTrue();

        ArgumentCaptor<WikiAppreciationRating> captor = ArgumentCaptor.forClass(WikiAppreciationRating.class);
        verify(appreciationRepositoryPort).save(captor.capture());
        WikiAppreciationRating saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(GENERATED_RATING_ID);
        assertThat(saved.getWikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue()).isEqualTo(4);
        assertThat(saved.getCreatedAt()).isEqualTo(FROZEN_NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(FROZEN_NOW);

        verify(clockPort).now();
        verify(idGeneratorPort).generate();
        verify(appreciationQueryPort).findSummaryByWikiArticleId(ARTICLE_ID);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("B. Đánh giá lần đầu: bài viết PUBLISHED FACTION -> hợp lệ và thành công")
    void shouldCreateFirstRatingForPublishedFaction() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.FACTION, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(FROZEN_NOW);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_RATING_ID);

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("5.0"), 1L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result.changed()).isTrue();
        assertThat(result.value()).isEqualTo(5);
        assertThat(result.count()).isEqualTo(1L);
        verify(appreciationRepositoryPort).save(any(WikiAppreciationRating.class));
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("C. Cập nhật đánh giá hiện có (3 -> 5): giữ nguyên ID và createdAt, cập nhật updatedAt, changed=true")
    void shouldUpdateExistingRatingWhenValueDiffers() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        UUID existingRatingId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        Instant originalCreatedAt = Instant.parse("2026-09-20T08:00:00Z");
        WikiAppreciationRating existingRating = WikiAppreciationRating.create(
                existingRatingId,
                ARTICLE_ID,
                USER_ID,
                3,
                originalCreatedAt
        );
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.of(existingRating));
        when(clockPort.now()).thenReturn(FROZEN_NOW);

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.5"), 2L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result.changed()).isTrue();
        assertThat(result.value()).isEqualTo(5);
        assertThat(result.count()).isEqualTo(2L);
        assertThat(result.average()).isEqualByComparingTo(new BigDecimal("4.5"));

        ArgumentCaptor<WikiAppreciationRating> captor = ArgumentCaptor.forClass(WikiAppreciationRating.class);
        verify(appreciationRepositoryPort).save(captor.capture());
        WikiAppreciationRating saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(existingRatingId);
        assertThat(saved.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(saved.getUpdatedAt()).isEqualTo(FROZEN_NOW);
        assertThat(saved.getValue()).isEqualTo(5);

        verify(clockPort).now();
        verify(idGeneratorPort, never()).generate();
        verify(appreciationQueryPort).findSummaryByWikiArticleId(ARTICLE_ID);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("D. Đánh giá cùng giá trị (5 -> 5): no-op, không gọi ClockPort, không gọi save, trả về changed=false")
    void shouldNoOpWhenSameValueSubmitted() {
        WikiArticleEligibilitySnapshot snapshot = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(snapshot));

        UUID existingRatingId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        Instant originalCreatedAt = Instant.parse("2026-09-20T08:00:00Z");
        WikiAppreciationRating existingRating = WikiAppreciationRating.create(
                existingRatingId,
                ARTICLE_ID,
                USER_ID,
                5,
                originalCreatedAt
        );
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.of(existingRating));

        WikiAppreciationSummary expectedSummary = new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.6667"), 3L);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID)).thenReturn(expectedSummary);

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result.changed()).isFalse();
        assertThat(result.value()).isEqualTo(5);
        assertThat(result.count()).isEqualTo(3L);
        assertThat(result.average()).isEqualByComparingTo(new BigDecimal("4.6667"));

        // Xác nhận không bao giờ gọi ClockPort, không gọi save, không generate ID
        verify(clockPort, never()).now();
        verify(appreciationRepositoryPort, never()).save(any());
        verify(idGeneratorPort, never()).generate();
        // Vẫn truy vấn fresh summary
        verify(appreciationQueryPort).findSummaryByWikiArticleId(ARTICLE_ID);
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, -1, 0, 6, 10})
    @DisplayName("E. Giá trị đánh giá không hợp lệ: bị từ chối ngay tại Command, không tra cứu bài viết, không gọi clock")
    void shouldRejectInvalidRatingValue(int invalidValue) {
        assertThatThrownBy(() -> new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, invalidValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Giá trị đánh giá phải nằm trong khoảng từ 1 đến 5 sao");

        verifyNoInteractions(wikiArticleQueryPort);
        verifyNoInteractions(appreciationRepositoryPort);
        verifyNoInteractions(appreciationQueryPort);
        verifyNoInteractions(clockPort);
    }

    @Test
    @DisplayName("F. Bài viết không tồn tại: ném WikiAppreciationTargetNotFoundException, không tra cứu rating, không gọi clock")
    void shouldThrowWhenArticleDoesNotExist() {
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.empty());

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verifyNoInteractions(appreciationRepositoryPort);
        verifyNoInteractions(appreciationQueryPort);
        verifyNoInteractions(clockPort);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("G. Bài viết DRAFT CHARACTER: từ chối vì chưa xuất bản (WikiAppreciationTargetNotFoundException)")
    void shouldRejectDraftCharacter() {
        WikiArticleEligibilitySnapshot draftArticle = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.DRAFT);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(draftArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verifyNoInteractions(appreciationRepositoryPort);
        verifyNoInteractions(clockPort);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("H. Bài viết ARCHIVED CHARACTER: từ chối vì đã lưu trữ (WikiAppreciationTargetNotFoundException)")
    void shouldRejectArchivedCharacter() {
        WikiArticleEligibilitySnapshot archivedArticle = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.ARCHIVED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(archivedArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verifyNoInteractions(appreciationRepositoryPort);
        verifyNoInteractions(clockPort);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("I. Bài viết PUBLISHED ITEM: từ chối vì loại bài không đủ điều kiện")
    void shouldRejectPublishedItem() {
        WikiArticleEligibilitySnapshot itemArticle = createEligibilitySnapshot(ArticleType.ITEM, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(itemArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verifyNoInteractions(appreciationRepositoryPort);
        verifyNoInteractions(clockPort);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("J. Bài viết PUBLISHED REALM hoặc loại không đủ điều kiện khác: từ chối")
    void shouldRejectPublishedRealm() {
        WikiArticleEligibilitySnapshot realmArticle = createEligibilitySnapshot(ArticleType.REALM, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(realmArticle));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(WikiAppreciationTargetNotFoundException.class)
                .hasMessageContaining(ARTICLE_ID.toString());

        verifyNoInteractions(appreciationRepositoryPort);
        verifyNoInteractions(clockPort);
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("K. Bài viết PUBLISHED CHARACTER: chấp nhận")
    void shouldAcceptPublishedCharacter() {
        WikiArticleEligibilitySnapshot article = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(FROZEN_NOW);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_RATING_ID);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("5.0"), 1L));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result.changed()).isTrue();
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("L. Bài viết PUBLISHED FACTION: chấp nhận")
    void shouldAcceptPublishedFaction() {
        WikiArticleEligibilitySnapshot article = createEligibilitySnapshot(ArticleType.FACTION, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(FROZEN_NOW);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_RATING_ID);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("5.0"), 1L));

        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5);
        SetWikiAppreciationResult result = useCase.execute(command);

        assertThat(result.changed()).isTrue();
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
        verify(wikiArticleQueryPort, never()).findDetailById(any());
    }

    @Test
    @DisplayName("Architectural guard: use case must never invoke findDetailById (admin detail with full content)")
    void shouldNeverCallFindDetailById() {
        WikiArticleEligibilitySnapshot article = createEligibilitySnapshot(ArticleType.CHARACTER, ArticleStatus.PUBLISHED);
        when(wikiArticleQueryPort.findEligibilityById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID)).thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(FROZEN_NOW);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_RATING_ID);
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("5.0"), 1L));

        useCase.execute(new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, 5));

        verify(wikiArticleQueryPort, never()).findDetailById(any());
        verify(wikiArticleQueryPort).findEligibilityById(ARTICLE_ID);
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
