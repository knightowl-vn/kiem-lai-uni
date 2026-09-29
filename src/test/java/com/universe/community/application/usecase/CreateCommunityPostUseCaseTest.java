package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
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
class CreateCommunityPostUseCaseTest {

    @Mock
    private CommunityPostRepositoryPort communityPostRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private CreateCommunityPostUseCase useCase;

    private static final UUID GENERATED_POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-29T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new CreateCommunityPostUseCase(communityPostRepositoryPort, idGeneratorPort, clockPort);
    }

    @Test
    @DisplayName("Should successfully orchestrate CommunityPost creation")
    void shouldCreatePostSuccessfully() {
        when(idGeneratorPort.generate()).thenReturn(GENERATED_POST_ID);
        when(clockPort.now()).thenReturn(FIXED_NOW);
        when(communityPostRepositoryPort.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateCommunityPostCommand command = new CreateCommunityPostCommand(
                ACTOR_ID,
                "Bài viết cộng đồng đầu tiên",
                MEDIA_ASSET_ID
        );

        CommunityPost created = useCase.execute(command);

        assertThat(created).isNotNull();
        assertThat(created.getId()).isEqualTo(GENERATED_POST_ID);
        assertThat(created.getAuthorUserId()).isEqualTo(ACTOR_ID);
        assertThat(created.getCaption()).isEqualTo("Bài viết cộng đồng đầu tiên");
        assertThat(created.getImageMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(created.getContentVersion()).isEqualTo(0);
        assertThat(created.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(created.getUpdatedAt()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<CommunityPost> postCaptor = ArgumentCaptor.forClass(CommunityPost.class);
        verify(communityPostRepositoryPort).save(postCaptor.capture());
        CommunityPost savedPost = postCaptor.getValue();
        assertThat(savedPost.getId()).isEqualTo(GENERATED_POST_ID);
        assertThat(savedPost.getCaption()).isEqualTo("Bài viết cộng đồng đầu tiên");
    }

    @Test
    @DisplayName("Should reject null command")
    void shouldRejectNullCommand() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreateCommunityPostCommand cannot be null");
    }
}
