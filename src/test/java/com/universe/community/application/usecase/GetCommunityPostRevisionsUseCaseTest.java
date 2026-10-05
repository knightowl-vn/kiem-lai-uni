package com.universe.community.application.usecase;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.contracts.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.contracts.port.CommunityPostQueryPort;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityPostRevisionsUseCase Unit Tests")
class GetCommunityPostRevisionsUseCaseTest {

    @Mock
    private CommunityPostQueryPort communityPostQueryPort;

    private GetCommunityPostRevisionsUseCase useCase;

    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        useCase = new GetCommunityPostRevisionsUseCase(communityPostQueryPort);
    }

    @Test
    @DisplayName("Should throw NullPointerException when postId is null")
    void shouldThrowExceptionWhenPostIdIsNull() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Post ID cannot be null.");
    }

    @Test
    @DisplayName("1. Missing post -> throws CommunityPostNotFoundException and does not invoke revision query")
    void shouldThrowNotFoundWhenPostDoesNotExist() {
        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(POST_ID))
                .isInstanceOf(CommunityPostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(communityPostQueryPort).findPublicPostById(POST_ID);
        verifyNoMoreInteractions(communityPostQueryPort);
    }

    @Test
    @DisplayName("2. Existing post + zero revisions -> returns empty list []")
    void shouldReturnEmptyListWhenPostHasNoRevisions() {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        CommunityPostPublicDTO postDTO = new CommunityPostPublicDTO(
                POST_ID,
                AUTHOR_ID,
                "Caption v0",
                null,
                0,
                now,
                now
        );

        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(postDTO));
        when(communityPostQueryPort.findPublicRevisionHistory(POST_ID)).thenReturn(List.of());

        List<CommunityPostRevisionPublicDTO> result = useCase.execute(POST_ID);

        assertThat(result).isNotNull().isEmpty();
        verify(communityPostQueryPort).findPublicPostById(POST_ID);
        verify(communityPostQueryPort).findPublicRevisionHistory(POST_ID);
        verifyNoMoreInteractions(communityPostQueryPort);
    }

    @Test
    @DisplayName("3. Existing post + revisions -> returns supplied revision list, query path used exactly once")
    void shouldReturnRevisionsWhenPostHasRevisions() {
        Instant t0 = Instant.parse("2026-10-02T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-02T10:10:00Z");
        Instant t2 = Instant.parse("2026-10-02T10:20:00Z");

        CommunityPostPublicDTO postDTO = new CommunityPostPublicDTO(
                POST_ID,
                AUTHOR_ID,
                "Caption v2",
                null,
                2,
                t0,
                t2
        );

        CommunityPostRevisionPublicDTO rev2 = new CommunityPostRevisionPublicDTO(
                UUID.randomUUID(),
                POST_ID,
                2,
                AUTHOR_ID,
                "Caption v1",
                "Caption v2",
                t2
        );
        CommunityPostRevisionPublicDTO rev1 = new CommunityPostRevisionPublicDTO(
                UUID.randomUUID(),
                POST_ID,
                1,
                AUTHOR_ID,
                "Caption v0",
                "Caption v1",
                t1
        );

        when(communityPostQueryPort.findPublicPostById(POST_ID)).thenReturn(Optional.of(postDTO));
        when(communityPostQueryPort.findPublicRevisionHistory(POST_ID)).thenReturn(List.of(rev2, rev1));

        List<CommunityPostRevisionPublicDTO> result = useCase.execute(POST_ID);

        assertThat(result).containsExactly(rev2, rev1);

        verify(communityPostQueryPort).findPublicPostById(POST_ID);
        verify(communityPostQueryPort).findPublicRevisionHistory(POST_ID);
        verifyNoMoreInteractions(communityPostQueryPort);
    }
}
