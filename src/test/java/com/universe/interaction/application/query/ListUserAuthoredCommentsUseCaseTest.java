package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.UserAuthoredCommentsQueryPort;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentItemDTO;
import com.universe.interaction.contracts.dto.authored.AuthoredCommentPageDTO;
import com.universe.interaction.domain.CommentTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListUserAuthoredCommentsUseCase Unit Tests")
class ListUserAuthoredCommentsUseCaseTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserAuthoredCommentsQueryPort queryPort;

    private ListUserAuthoredCommentsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListUserAuthoredCommentsUseCase(queryPort);
    }

    @Test
    @DisplayName("Throws NullPointerException when authorUserId is null")
    void shouldThrowWhenAuthorUserIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null, UserCommentContextFilter.ALL, 0, 20))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("authorUserId cannot be null");
    }

    @Test
    @DisplayName("Filter ALL passes both NOVEL_CHAPTER and WIKI_ARTICLE target types to port")
    void shouldQueryAllTargetTypesWhenFilterIsAll() {
        AuthoredCommentPageDTO expected = AuthoredCommentPageDTO.empty(0, 20);
        when(queryPort.findAuthoredComments(eq(USER_ID), any(), eq(0), eq(20)))
                .thenReturn(expected);

        AuthoredCommentPageDTO result = useCase.execute(USER_ID, UserCommentContextFilter.ALL, 0, 20);

        assertThat(result).isSameAs(expected);
        verify(queryPort).findAuthoredComments(
                eq(USER_ID),
                eq(Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE)),
                eq(0),
                eq(20)
        );
    }

    @Test
    @DisplayName("Filter NOVEL passes only NOVEL_CHAPTER target type to port")
    void shouldQueryNovelTargetTypeWhenFilterIsNovel() {
        AuthoredCommentPageDTO expected = AuthoredCommentPageDTO.empty(0, 20);
        when(queryPort.findAuthoredComments(eq(USER_ID), any(), eq(0), eq(20)))
                .thenReturn(expected);

        AuthoredCommentPageDTO result = useCase.execute(USER_ID, UserCommentContextFilter.NOVEL, 0, 20);

        assertThat(result).isSameAs(expected);
        verify(queryPort).findAuthoredComments(
                eq(USER_ID),
                eq(Set.of(CommentTargetType.NOVEL_CHAPTER)),
                eq(0),
                eq(20)
        );
    }

    @Test
    @DisplayName("Filter WIKI passes only WIKI_ARTICLE target type to port")
    void shouldQueryWikiTargetTypeWhenFilterIsWiki() {
        AuthoredCommentPageDTO expected = AuthoredCommentPageDTO.empty(0, 20);
        when(queryPort.findAuthoredComments(eq(USER_ID), any(), eq(0), eq(20)))
                .thenReturn(expected);

        AuthoredCommentPageDTO result = useCase.execute(USER_ID, UserCommentContextFilter.WIKI, 0, 20);

        assertThat(result).isSameAs(expected);
        verify(queryPort).findAuthoredComments(
                eq(USER_ID),
                eq(Set.of(CommentTargetType.WIKI_ARTICLE)),
                eq(0),
                eq(20)
        );
    }

    @Test
    @DisplayName("Defaults filter to ALL and normalizes negative page and invalid size")
    void shouldDefaultFilterAndNormalizePageAndSize() {
        AuthoredCommentPageDTO expected = AuthoredCommentPageDTO.empty(0, 20);
        when(queryPort.findAuthoredComments(eq(USER_ID), any(), eq(0), eq(20)))
                .thenReturn(expected);

        AuthoredCommentPageDTO result = useCase.execute(USER_ID, null, -5, 0);

        assertThat(result).isSameAs(expected);
        verify(queryPort).findAuthoredComments(
                eq(USER_ID),
                eq(Set.of(CommentTargetType.NOVEL_CHAPTER, CommentTargetType.WIKI_ARTICLE)),
                eq(0),
                eq(ListUserAuthoredCommentsUseCase.DEFAULT_PAGE_SIZE)
        );
    }
}
