package com.universe.interaction.domain.reaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReactionTarget Value Object Unit Tests")
class ReactionTargetTest {

    @Test
    @DisplayName("Should create ReactionTarget with factory methods")
    void shouldCreateReactionTargetWithFactoryMethods() {
        UUID chapterId = UUID.randomUUID();
        ReactionTarget chapterTarget = ReactionTarget.novelChapter(chapterId);
        assertThat(chapterTarget.type()).isEqualTo(ReactionTargetType.NOVEL_CHAPTER);
        assertThat(chapterTarget.targetId()).isEqualTo(chapterId);

        UUID commentId = UUID.randomUUID();
        ReactionTarget commentTarget = ReactionTarget.comment(commentId);
        assertThat(commentTarget.type()).isEqualTo(ReactionTargetType.COMMENT);
        assertThat(commentTarget.targetId()).isEqualTo(commentId);

        UUID episodeId = UUID.randomUUID();
        ReactionTarget episodeTarget = ReactionTarget.donghuaEpisode(episodeId);
        assertThat(episodeTarget.type()).isEqualTo(ReactionTargetType.DONGHUA_EPISODE);
        assertThat(episodeTarget.targetId()).isEqualTo(episodeId);
    }

    @Test
    @DisplayName("Should validate non-null type and targetId")
    void shouldValidateNonNullComponents() {
        UUID targetId = UUID.randomUUID();

        assertThatThrownBy(() -> new ReactionTarget(null, targetId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReactionTargetType cannot be null");

        assertThatThrownBy(() -> new ReactionTarget(ReactionTargetType.NOVEL_CHAPTER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Target ID cannot be null");
    }

    @Test
    @DisplayName("Should evaluate equality and hashCode based on type and targetId")
    void shouldEvaluateEqualityAndHashCode() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        ReactionTarget targetA1 = ReactionTarget.novelChapter(id1);
        ReactionTarget targetA2 = ReactionTarget.novelChapter(id1);
        ReactionTarget targetB = ReactionTarget.novelChapter(id2);
        ReactionTarget targetC = ReactionTarget.comment(id1);

        assertThat(targetA1).isEqualTo(targetA2);
        assertThat(targetA1.hashCode()).isEqualTo(targetA2.hashCode());

        assertThat(targetA1).isNotEqualTo(targetB);
        assertThat(targetA1).isNotEqualTo(targetC);
    }
}
