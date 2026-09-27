package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.CommentTargetNotEligibleException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.CommentTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateRootCommentUseCase Unit Tests")
class CreateRootCommentUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommentTargetEligibilityPort eligibilityPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private CreateRootCommentUseCase useCase;

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GENERATED_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHAPTER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new CreateRootCommentUseCase(
                commentRepositoryPort,
                eligibilityPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Should create and save active root comment when target is eligible")
    void shouldCreateRootCommentSuccessfully() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        CreateRootCommentCommand command = new CreateRootCommentCommand(ACTOR_ID, target, "Great chapter!");

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(idGeneratorPort.generate()).thenReturn(GENERATED_ID);
        when(clockPort.now()).thenReturn(NOW);
        when(commentRepositoryPort.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        Comment result = useCase.execute(command);

        assertThat(result.getId()).isEqualTo(GENERATED_ID);
        assertThat(result.getAuthorUserId()).isEqualTo(ACTOR_ID);
        assertThat(result.getTarget()).isEqualTo(target);
        assertThat(result.getBody()).isEqualTo("Great chapter!");
        assertThat(result.getStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(result.isRoot()).isTrue();
        assertThat(result.getParentCommentId()).isNull();
        assertThat(result.getThreadRootCommentId()).isNull();
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
        assertThat(result.getUpdatedAt()).isEqualTo(NOW);
        assertThat(result.getDeletedAt()).isNull();

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepositoryPort).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(GENERATED_ID);
    }

    @Test
    @DisplayName("Should reject root comment creation when target is not eligible")
    void shouldRejectWhenTargetNotEligible() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);
        CreateRootCommentCommand command = new CreateRootCommentCommand(ACTOR_ID, target, "Nice!");

        when(eligibilityPort.isEligible(target)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommentTargetNotEligibleException.class)
                .hasMessageContaining("Comment target is not eligible for comments");
    }

    @Test
    @DisplayName("Should validate command invariants")
    void shouldValidateCommandInvariants() {
        CommentTarget target = CommentTarget.novelChapter(CHAPTER_ID);

        assertThatThrownBy(() -> new CreateRootCommentCommand(null, target, "Body"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CreateRootCommentCommand(ACTOR_ID, null, "Body"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CreateRootCommentCommand(ACTOR_ID, target, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CreateRootCommentCommand(ACTOR_ID, target, "   "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
