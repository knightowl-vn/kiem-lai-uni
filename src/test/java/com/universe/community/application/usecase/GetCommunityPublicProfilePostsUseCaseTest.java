package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityAuthorProfileDetails;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.domain.exception.CommunityPostValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommunityPublicProfilePostsUseCase Unit Tests")
class GetCommunityPublicProfilePostsUseCaseTest {

    @Mock
    private CommunityAuthorProfilePort authorProfilePort;

    @Mock
    private GetCommunityAuthorPostsUseCase getCommunityAuthorPostsUseCase;

    private GetCommunityPublicProfilePostsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetCommunityPublicProfilePostsUseCase(authorProfilePort, getCommunityAuthorPostsUseCase);
    }

    @Test
    @DisplayName("Should throw NullPointerException when constructor dependencies are null")
    void shouldThrowWhenConstructorArgsAreNull() {
        assertThatThrownBy(() -> new GetCommunityPublicProfilePostsUseCase(null, getCommunityAuthorPostsUseCase))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("CommunityAuthorProfilePort cannot be null.");

        assertThatThrownBy(() -> new GetCommunityPublicProfilePostsUseCase(authorProfilePort, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("GetCommunityAuthorPostsUseCase cannot be null.");
    }

    @Test
    @DisplayName("Should successfully retrieve paginated posts for valid author handle")
    void shouldRetrievePaginatedPostsForAuthorSuccessfully() {
        UUID authorId = UUID.randomUUID();
        String handle = "linh_dao";
        CommunityAuthorProfileDetails author = new CommunityAuthorProfileDetails(
                authorId,
                handle,
                "Linh Đạo",
                null,
                null
        );

        UUID p1Id = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        CommunityPostFeedItemDTO postItem = new CommunityPostFeedItemDTO(
                p1Id,
                authorId,
                "Linh Đạo",
                "linh_dao",
                null,
                "Bài viết trang 2",
                null,
                null,
                0,
                5L,
                1L,
                6L,
                now,
                now
        );
        CommunityNewestFeedResponseDTO feedResponse = new CommunityNewestFeedResponseDTO(
                List.of(postItem),
                "next-cursor-page3",
                10,
                true
        );

        when(authorProfilePort.findAuthorProfileByHandle(handle)).thenReturn(Optional.of(author));
        when(getCommunityAuthorPostsUseCase.execute(eq(authorId), eq("current-cursor"), eq(10)))
                .thenReturn(feedResponse);

        Optional<CommunityNewestFeedResponseDTO> resultOpt = useCase.execute(handle, "current-cursor", 10);

        assertThat(resultOpt).isPresent();
        CommunityNewestFeedResponseDTO result = resultOpt.get();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).caption()).isEqualTo("Bài viết trang 2");
        assertThat(result.nextCursor()).isEqualTo("next-cursor-page3");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.size()).isEqualTo(10);

        verify(authorProfilePort).findAuthorProfileByHandle(handle);
        verify(getCommunityAuthorPostsUseCase).execute(authorId, "current-cursor", 10);
    }

    @Test
    @DisplayName("Should return Optional.empty() when author profile is not found in port")
    void shouldReturnEmptyWhenAuthorProfileNotFoundInPort() {
        when(authorProfilePort.findAuthorProfileByHandle("missing_user")).thenReturn(Optional.empty());

        Optional<CommunityNewestFeedResponseDTO> resultOpt = useCase.execute("missing_user", null, 20);

        assertThat(resultOpt).isEmpty();
        verify(authorProfilePort).findAuthorProfileByHandle("missing_user");
        verifyNoInteractions(getCommunityAuthorPostsUseCase);
    }

    @Test
    @DisplayName("Should return Optional.empty() when handle is null")
    void shouldReturnEmptyWhenHandleIsNull() {
        Optional<CommunityNewestFeedResponseDTO> resultOpt = useCase.execute(null, "cursor", 20);

        assertThat(resultOpt).isEmpty();
        verifyNoInteractions(authorProfilePort);
        verifyNoInteractions(getCommunityAuthorPostsUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("Should return Optional.empty() when handle is empty or whitespace-only")
    void shouldReturnEmptyWhenHandleIsBlank(String blankHandle) {
        Optional<CommunityNewestFeedResponseDTO> resultOpt = useCase.execute(blankHandle, "cursor", 20);

        assertThat(resultOpt).isEmpty();
        verifyNoInteractions(authorProfilePort);
        verifyNoInteractions(getCommunityAuthorPostsUseCase);
    }

    @Test
    @DisplayName("Should propagate validation exceptions from GetCommunityAuthorPostsUseCase")
    void shouldPropagateValidationExceptions() {
        UUID authorId = UUID.randomUUID();
        String handle = "valid_user";
        CommunityAuthorProfileDetails author = new CommunityAuthorProfileDetails(
                authorId,
                handle,
                "Valid User",
                null,
                null
        );

        when(authorProfilePort.findAuthorProfileByHandle(handle)).thenReturn(Optional.of(author));
        when(getCommunityAuthorPostsUseCase.execute(eq(authorId), eq("malformed-cursor"), eq(20)))
                .thenThrow(new CommunityPostValidationException("Invalid cursor format."));

        assertThatThrownBy(() -> useCase.execute(handle, "malformed-cursor", 20))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessage("Invalid cursor format.");
    }
}
