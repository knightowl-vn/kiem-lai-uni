package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.service.CommunityPostCreationGuardService;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException.Reason;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.when;

class CreateCommunityPostWithImageUseCaseTest {

    private CreateCommunityPostUseCase createCommunityPostUseCase;
    private CommunityPostImageUploadUseCase imageUploadUseCase;
    private CommunityPostCreationGuardService creationGuardService;
    private ClockPort clockPort;
    private TransactionTemplate transactionTemplate;
    private CreateCommunityPostWithImageUseCase orchestrator;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-29T10:00:00Z");

    @BeforeEach
    void setUp() {
        createCommunityPostUseCase = mock(CreateCommunityPostUseCase.class);
        imageUploadUseCase = mock(CommunityPostImageUploadUseCase.class);
        creationGuardService = mock(CommunityPostCreationGuardService.class);
        clockPort = mock(ClockPort.class);
        when(clockPort.now()).thenReturn(FIXED_NOW);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        orchestrator = new CreateCommunityPostWithImageUseCase(
                createCommunityPostUseCase,
                imageUploadUseCase,
                creationGuardService,
                clockPort,
                transactionTemplate
        );
    }

    @Test
    @DisplayName("Should create caption-only post when image stream is null")
    void shouldCreateCaptionOnlyPostWhenImageNull() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Caption only post test";
        Instant now = FIXED_NOW;
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

        verify(creationGuardService).acquireAuthorLock(actorUserId);
        verify(creationGuardService).evaluateEligibility(actorUserId, caption, FIXED_NOW);

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
        Instant now = FIXED_NOW;
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

        verify(creationGuardService).acquireAuthorLock(actorUserId);
        verify(creationGuardService).evaluateEligibility(actorUserId, caption, FIXED_NOW);

        ArgumentCaptor<CreateCommunityPostCommand> captor = ArgumentCaptor.forClass(CreateCommunityPostCommand.class);
        verify(createCommunityPostUseCase).execute(captor.capture());
        assertThat(captor.getValue().imageMediaAssetId()).isEqualTo(assetId);
        assertThat(captor.getValue().caption()).isEqualTo(caption);
        assertThat(captor.getValue().actorUserId()).isEqualTo(actorUserId);
    }

    @Test
    @DisplayName("Should short-circuit and NEVER upload image when COOLDOWN rate limit is violated (MS-07B8.5.5 Section 15)")
    void shouldShortCircuitAndNeverUploadImageWhenCooldownViolated() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Image post under cooldown";
        byte[] imageData = "valid-jpeg-bytes".getBytes();

        CommunityPostCreationRateLimitException cooldownEx =
                new CommunityPostCreationRateLimitException(Reason.COOLDOWN, 45L);
        doThrow(cooldownEx).when(creationGuardService).evaluateEligibility(actorUserId, caption, FIXED_NOW);

        assertThatThrownBy(() -> orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        ))
                .isSameAs(cooldownEx)
                .hasMessageContaining("Bạn đang đăng bài quá nhanh. Vui lòng thử lại sau.");

        // Guaranteed: uploadImage is NEVER called!
        verify(imageUploadUseCase, never()).uploadImage(any(), any(Long.class), any(), any());
        verify(createCommunityPostUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Should short-circuit and NEVER upload image when DUPLICATE_CAPTION rate limit is violated (MS-07B8.5.5 Section 15)")
    void shouldShortCircuitAndNeverUploadImageWhenDuplicateCaptionViolated() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Duplicate caption attempt";
        byte[] imageData = "valid-jpeg-bytes".getBytes();

        CommunityPostCreationRateLimitException duplicateEx =
                new CommunityPostCreationRateLimitException(Reason.DUPLICATE_CAPTION, 80000L);
        doThrow(duplicateEx).when(creationGuardService).evaluateEligibility(actorUserId, caption, FIXED_NOW);

        assertThatThrownBy(() -> orchestrator.execute(
                actorUserId,
                caption,
                new ByteArrayInputStream(imageData),
                imageData.length,
                "image/jpeg",
                "test.jpg"
        ))
                .isSameAs(duplicateEx)
                .hasMessageContaining("Bạn đã đăng nội dung tương tự trong 24 giờ qua.");

        // Guaranteed: uploadImage is NEVER called!
        verify(imageUploadUseCase, never()).uploadImage(any(), any(Long.class), any(), any());
        verify(createCommunityPostUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Should compensate uploaded image when subsequent post creation throws exception")
    void shouldCompensateUploadedImageWhenPostCreationFails() {
        UUID actorUserId = UUID.randomUUID();
        String caption = "Post creation fails";
        UUID assetId = UUID.randomUUID();
        byte[] imageData = "bytes".getBytes();
        RuntimeException dbException = new RuntimeException("Database error saving post");

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
                new CreateCommunityPostWithImageUseCase(
                        createCommunityPostUseCase,
                        realImageUploadUseCase,
                        creationGuardService,
                        clockPort,
                        transactionTemplate
                );

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
