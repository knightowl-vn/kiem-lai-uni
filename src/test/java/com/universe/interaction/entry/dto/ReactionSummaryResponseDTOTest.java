package com.universe.interaction.entry.dto;

import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReactionSummaryResponseDTO Unit Tests")
class ReactionSummaryResponseDTOTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Throws NullPointerException when summary is null")
    void shouldThrowWhenSummaryIsNull() {
        assertThatThrownBy(() -> ReactionSummaryResponseDTO.from(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReactionSummary");
    }

    @Test
    @DisplayName("Maps all reaction types to string keys and preserves scalar fields")
    void shouldMapAllReactionTypesToStringKeys() {
        ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        counts.put(ReactionType.LOVE, 12L);
        counts.put(ReactionType.FIRE, 4L);
        counts.put(ReactionType.HAHA, 2L);
        counts.put(ReactionType.SAD, 1L);

        ReactionSummary summary = ReactionSummary.of(
                target,
                counts,
                ReactionType.LOVE
        );

        ReactionSummaryResponseDTO dto = ReactionSummaryResponseDTO.from(summary);

        assertThat(dto.targetType()).isEqualTo("NOVEL_CHAPTER");
        assertThat(dto.targetId()).isEqualTo(TARGET_ID);
        assertThat(dto.totalCount()).isEqualTo(19L);
        assertThat(dto.currentUserReaction()).isEqualTo("LOVE");
        assertThat(dto.counts())
                .containsEntry("LOVE", 12L)
                .containsEntry("FIRE", 4L)
                .containsEntry("HAHA", 2L)
                .containsEntry("SAD", 1L);
    }

    @Test
    @DisplayName("Maps null currentUserReaction correctly for unreacted summary")
    void shouldMapNullCurrentUserReaction() {
        ReactionTarget target = ReactionTarget.comment(TARGET_ID);
        ReactionSummary summary = ReactionSummary.of(
                target,
                Map.of(),
                null
        );

        ReactionSummaryResponseDTO dto = ReactionSummaryResponseDTO.from(summary);

        assertThat(dto.targetType()).isEqualTo("COMMENT");
        assertThat(dto.targetId()).isEqualTo(TARGET_ID);
        assertThat(dto.totalCount()).isEqualTo(0L);
        assertThat(dto.currentUserReaction()).isNull();
        assertThat(dto.counts())
                .containsEntry("LOVE", 0L)
                .containsEntry("FIRE", 0L)
                .containsEntry("HAHA", 0L)
                .containsEntry("SAD", 0L);
    }
}
