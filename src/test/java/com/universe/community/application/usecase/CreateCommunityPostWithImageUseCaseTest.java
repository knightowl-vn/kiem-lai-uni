package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateCommunityPostWithImageUseCaseTest {

    private CreateCommunityPostUseCase createCommunityPostUseCase;
    private CommunityPostImageUploadUseCase imageUploadUseCase;
    private CreateCommunityPostWithImageUseCase orchestrator;

    @BeforeEach
    void setUp() {
        createCommunityPostUseCase = mock(CreateCommunityPostUseCase.class);
        imageUploadUseCase = mock(CommunityPostImageUploadUseCase.class);
        orchestrator = new CreateCommunityPostWithImageUseCase(createCommunityPostUseCase, imageUploadUseCase);
    }

    @Test
    @DisplayName("Should create caption-only post when image stream is null")
    void shouldCreateCaptionOnlyPostWhenImageNull() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Caption only post test";
        Instant now = Instant.now();
        UUID postId = UUID.randomUUID();

        CommunityPost expectedPost = CommunityPost.create(postId, actorUserId, caption, null, CommunityPostStatus.PUBLISHED, now, now, null);
        when(createCommunityPostUseCase.execute(any(CreateCommunityPostCommand.class))).thenReturn(expectedPost);

        CommunityPost actualPost = orchestrator.execute(
                actorUserId,
                caption,
                null,
                0,
                null,
                null
        );

        assertThat(actualPost).isEqualTo(expectedPost);
        assertThat(actualPost.getImageMediaAssetId()).isNull();

        ArgumentCaptor<CreateCommunityPostCommand> captor = ArgumentCaptor.forClass(CreateCommunityPostCommand.class);
        verify(createCommunityPostUseCase).execute(captor.capture());
        assertThat(captor.getValue().imageMediaAssetId()).isNull();
        assertThat(captor.getValue().caption()).isEqualTo(caption);
        assertThat(captor.getValue().actorUserId()).isEqualTo(actorUserId);

        verify(imageUploadUseCase, never()).uploadImage(any(), any(Long.class), any(), any());
    }

    @Test
    @DisplayName("Should upload image and create post with imageMediaAssetId")
    void shouldUploadImageAndCreatePostWithImageAssetId() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Post with image";
        UUID assetId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] imageData = "valid-jpeg-bytes".getBytes();

        when(imageUploadUseCase.uploadImage(any(InputStream.class), eq((long) imageData.length), eq("image/jpeg"), eq("test.jpg")))
                .thenReturn(assetId);

        CommunityPost expectedPost = CommunityPost.create(postId, actorUserId, caption, assetId, CommunityPostStatus.PUBLISHED, now, now, null);
        when(createCommunityPostUseCase.execute(any(CreateCommunityPostCommand.class))).thenReturn(expectedPost);

        CommunityPost actualPost = orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        );

        assertThat(actualPost).isEqualTo(expectedPost);
        assertThat(actualPost.getImageMediaAssetId()).isEqualTo(assetId);

        ArgumentCaptor<CreateCommunityPostCommand> captor = ArgumentCaptor.forClass(CreateCommunityPostCommand.class);
        verify(createCommunityPostUseCase).execute(captor.capture());
        assertThat(captor.getValue().imageMediaAssetId()).isEqualTo(assetId);
        assertThat(captor.getValue().caption()).isEqualTo(caption);

        verify(imageUploadUseCase, never()).compensateUpload(any(), any());
    }

    @Test
    @DisplayName("Should not call post creation when image upload fails")
    void shouldNotCallPostCreationWhenImageUploadFails() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Post image upload fails";
        byte[] imageData = "bad-bytes".getBytes();

        when(imageUploadUseCase.uploadImage(any(), any(Long.class), any(), any()))
                .thenThrow(new RuntimeException("Cloudinary upload failed"));

        assertThatThrownBy(() -> orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cloudinary upload failed");

        verify(createCommunityPostUseCase, never()).execute(any());
        verify(imageUploadUseCase, never()).compensateUpload(any(), any());
    }

    @Test
    @DisplayName("Should compensate uploaded image when post creation fails")
    void shouldCompensateUploadedImageWhenPostCreationFails() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Post creation fails after upload";
        UUID assetId = UUID.randomUUID();
        byte[] imageData = "bytes".getBytes();
        RuntimeException dbException = new RuntimeException("Database unique constraint violation");

        when(imageUploadUseCase.uploadImage(any(), any(Long.class), any(), any())).thenReturn(assetId);
        when(createCommunityPostUseCase.execute(any(CreateCommunityPostCommand.class))).thenThrow(dbException);

        assertThatThrownBy(() -> orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        ))
                .isSameAs(dbException);

        verify(imageUploadUseCase).compensateUpload(assetId, dbException);
    }

    @Test
    @DisplayName("Should preserve original exception and attach suppressed error when compensation fails")
    void shouldPreserveOriginalExceptionWhenCompensationFails() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Post creation and compensation both fail";
        UUID assetId = UUID.randomUUID();
        byte[] imageData = "bytes".getBytes();
        RuntimeException dbException = new RuntimeException("Database connection timeout");
        RuntimeException compException = new RuntimeException("Media service compensation delete timeout");

        when(imageUploadUseCase.uploadImage(any(), any(Long.class), any(), any())).thenReturn(assetId);
        when(createCommunityPostUseCase.execute(any(CreateCommunityPostCommand.class))).thenThrow(dbException);

        doAnswer(invocation -> {
            RuntimeException primary = invocation.getArgument(1);
            primary.addSuppressed(compException);
            return null;
        }).when(imageUploadUseCase).compensateUpload(eq(assetId), eq(dbException));

        assertThatThrownBy(() -> orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        ))
                .isSameAs(dbException)
                .satisfies(ex -> assertThat(ex.getSuppressed()).containsExactly(compException));
    }

    @Test
    @DisplayName("Should not create or persist post when real Media RasterContentSignatureValidator rejects MIME mismatch")
    void shouldNotPersistPostWhenRealMediaSignatureValidatorRejectsMismatchedImage() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Post with mismatched image";

        com.universe.media.application.storage.MediaStorageRoutingService routingService =
                mock(com.universe.media.application.storage.MediaStorageRoutingService.class);
        com.universe.media.application.ports.storage.StorageProviderResolverPort storageResolver =
                mock(com.universe.media.application.ports.storage.StorageProviderResolverPort.class);
        com.universe.media.application.asset.RegisterMediaAssetUseCase registerUseCase =
                mock(com.universe.media.application.asset.RegisterMediaAssetUseCase.class);
        com.universe.media.application.asset.RasterContentSignatureValidator realValidator =
                new com.universe.media.application.asset.RasterContentSignatureValidator();

        com.universe.media.application.asset.UploadMediaAssetUseCase realUploadUseCase =
                new com.universe.media.application.asset.UploadMediaAssetUseCase(
                        routingService,
                        storageResolver,
                        registerUseCase,
                        realValidator
                );

        MediaContract realMediaContract = mock(MediaContract.class);
        when(realMediaContract.uploadAsset(any(com.universe.media.contracts.dto.UploadMediaAssetRequestDTO.class))).thenAnswer(invocation -> {
            com.universe.media.contracts.dto.UploadMediaAssetRequestDTO req = invocation.getArgument(0);
            com.universe.media.application.asset.UploadMediaAssetCommand cmd =
                    new com.universe.media.application.asset.UploadMediaAssetCommand(
                            req.content(),
                            req.sizeBytes(),
                            req.mimeType(),
                            com.universe.media.domain.MediaType.IMAGE,
                            com.universe.media.domain.MediaVisibility.PUBLIC,
                            req.originalFilename(),
                            req.clientTag()
                    );
            realUploadUseCase.execute(cmd);
            return null;
        });

        CommunityPostImageUploadUseCase realImageUploadUseCase = new CommunityPostImageUploadUseCase(realMediaContract);
        CreateCommunityPostWithImageUseCase realOrchestrator =
                new CreateCommunityPostWithImageUseCase(createCommunityPostUseCase, realImageUploadUseCase);

        // Non-JPEG bytes (e.g. text / garbage) declared as image/jpeg
        byte[] invalidBytes = "This is not a JPEG file at all".getBytes();

        assertThatThrownBy(() -> realOrchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(invalidBytes),
                invalidBytes.length,
                "image/jpeg",
                "bad.jpg"
        ))
                .isInstanceOf(com.universe.media.application.exceptions.UploadContentMimeMismatchException.class)
                .hasMessageContaining("does not match declared raster MIME type");

        verify(createCommunityPostUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Should fail closed and compensate image upload when publication settings are missing/unconfigured")
    void shouldCompensateImageAndFailClosedWhenPublicationSettingsMissing() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Image post fails closed";
        UUID assetId = UUID.randomUUID();
        byte[] imageData = "valid-jpeg-bytes".getBytes();
        IllegalStateException settingsEx = new IllegalStateException("Community publication settings are not configured.");

        when(imageUploadUseCase.uploadImage(any(), any(Long.class), any(), any())).thenReturn(assetId);
        when(createCommunityPostUseCase.execute(any(CreateCommunityPostCommand.class))).thenThrow(settingsEx);

        assertThatThrownBy(() -> orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        ))
                .isSameAs(settingsEx)
                .hasMessageContaining("Community publication settings are not configured.");

        verify(imageUploadUseCase).compensateUpload(assetId, settingsEx);
    }
}
