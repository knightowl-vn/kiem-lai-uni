package com.universe.wiki.application.appreciation;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SetWikiAppreciationAttemptExecutor Unit Tests (MS-05F7 & F9)")
class SetWikiAppreciationAttemptExecutorTest {

    private static final UUID ARTICLE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GENERATED_RATING_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final Instant FROZEN_NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Mock
    private WikiAppreciationRepositoryPort appreciationRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private SetWikiAppreciationAttemptExecutor attemptExecutor;

    @BeforeEach
    void setUp() {
        attemptExecutor = new SetWikiAppreciationAttemptExecutor(
                appreciationRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("A. Đánh giá mới (no current row): gọi ClockPort 1 lần, IdGeneratorPort 1 lần, save rating mới, changed=true")
    void shouldCreateNewRatingWhenNoCurrentRowExists() {
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(FROZEN_NOW);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_RATING_ID);

        WikiAppreciationScore score = WikiAppreciationScore.fromStars(new BigDecimal("4.0"));
        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, score);
        AppreciationMutationAttemptResult result = attemptExecutor.executeAttempt(command);

        assertThat(result).isNotNull();
        assertThat(result.changed()).isTrue();

        ArgumentCaptor<WikiAppreciationRating> captor = ArgumentCaptor.forClass(WikiAppreciationRating.class);
        verify(appreciationRepositoryPort).save(captor.capture());
        WikiAppreciationRating saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(GENERATED_RATING_ID);
        assertThat(saved.getWikiArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getScore()).isEqualTo(score);
        assertThat(saved.getValue()).isEqualTo(8);
        assertThat(saved.getCreatedAt()).isEqualTo(FROZEN_NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(FROZEN_NOW);

        verify(clockPort).now();
        verify(idGeneratorPort).generate();
    }

    @Test
    @DisplayName("B. Cập nhật khác điểm (existing row, different score): gọi ClockPort 1 lần, KHÔNG gọi IdGenerator, giữ nguyên ID, save, changed=true")
    void shouldUpdateExistingRatingWhenValueDiffers() {
        UUID existingRatingId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        Instant originalCreatedAt = Instant.parse("2026-09-20T08:00:00Z");
        WikiAppreciationRating existingRating = WikiAppreciationRating.create(
                existingRatingId,
                ARTICLE_ID,
                USER_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("3.0")),
                originalCreatedAt
        );

        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID))
                .thenReturn(Optional.of(existingRating));
        when(clockPort.now()).thenReturn(FROZEN_NOW);

        WikiAppreciationScore newScore = WikiAppreciationScore.fromStars(new BigDecimal("4.5"));
        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, newScore);
        AppreciationMutationAttemptResult result = attemptExecutor.executeAttempt(command);

        assertThat(result).isNotNull();
        assertThat(result.changed()).isTrue();

        ArgumentCaptor<WikiAppreciationRating> captor = ArgumentCaptor.forClass(WikiAppreciationRating.class);
        verify(appreciationRepositoryPort).save(captor.capture());
        WikiAppreciationRating saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(existingRatingId);
        assertThat(saved.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(saved.getUpdatedAt()).isEqualTo(FROZEN_NOW);
        assertThat(saved.getScore()).isEqualTo(newScore);
        assertThat(saved.getValue()).isEqualTo(9);

        verify(clockPort).now();
        verify(idGeneratorPort, never()).generate();
    }

    @Test
    @DisplayName("C. Đánh giá cùng điểm (existing row, same score): KHÔNG gọi ClockPort, KHÔNG gọi IdGenerator, KHÔNG gọi save, updatedAt giữ nguyên, changed=false")
    void shouldNoOpWhenSameValueSubmitted() {
        UUID existingRatingId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        Instant originalCreatedAt = Instant.parse("2026-09-20T08:00:00Z");
        WikiAppreciationRating existingRating = WikiAppreciationRating.create(
                existingRatingId,
                ARTICLE_ID,
                USER_ID,
                WikiAppreciationScore.fromStars(new BigDecimal("4.5")),
                originalCreatedAt
        );

        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, USER_ID))
                .thenReturn(Optional.of(existingRating));

        WikiAppreciationScore sameScore = WikiAppreciationScore.fromHalfStarUnits(9);
        SetWikiAppreciationCommand command = new SetWikiAppreciationCommand(ARTICLE_ID, USER_ID, sameScore);
        AppreciationMutationAttemptResult result = attemptExecutor.executeAttempt(command);

        assertThat(result).isNotNull();
        assertThat(result.changed()).isFalse();

        // Sequential same-value invariant: ZERO clock, ZERO ID, ZERO save
        verify(clockPort, never()).now();
        verify(idGeneratorPort, never()).generate();
        verify(appreciationRepositoryPort, never()).save(any());
        assertThat(existingRating.getUpdatedAt()).isEqualTo(originalCreatedAt);
    }

    @Test
    @DisplayName("Từ chối command null")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> attemptExecutor.executeAttempt(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SetWikiAppreciationCommand không được để trống.");
    }
}
