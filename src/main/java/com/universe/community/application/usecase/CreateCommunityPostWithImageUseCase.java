package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.application.service.CommunityPostCreationGuardService;
import com.universe.community.domain.CommunityPost;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Application orchestrator use case for creating a Community Post with an optional image upload and failure compensation.
 * Enforces transactional anti-spam checks prior to binary I/O under the author's DB serialization lock
 * to guarantee that rate-limited requests NEVER perform media upload.
 */
@Service
public class CreateCommunityPostWithImageUseCase {

    private final CreateCommunityPostUseCase createCommunityPostUseCase;
    private final CommunityPostImageUploadUseCase imageUploadUseCase;
    private final CommunityPostCreationGuardService creationGuardService;
    private final ClockPort clockPort;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public CreateCommunityPostWithImageUseCase(
            CreateCommunityPostUseCase createCommunityPostUseCase,
            CommunityPostImageUploadUseCase imageUploadUseCase,
            CommunityPostCreationGuardService creationGuardService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
    ) {
        this(
                createCommunityPostUseCase,
                imageUploadUseCase,
                creationGuardService,
                clockPort,
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "PlatformTransactionManager cannot be null."))
        );
    }

    CreateCommunityPostWithImageUseCase(
            CreateCommunityPostUseCase createCommunityPostUseCase,
            CommunityPostImageUploadUseCase imageUploadUseCase,
            CommunityPostCreationGuardService creationGuardService,
            ClockPort clockPort,
            TransactionTemplate transactionTemplate
    ) {
        this.createCommunityPostUseCase = Objects.requireNonNull(
                createCommunityPostUseCase,
                "CreateCommunityPostUseCase cannot be null."
        );
        this.imageUploadUseCase = Objects.requireNonNull(
                imageUploadUseCase,
                "CommunityPostImageUploadUseCase cannot be null."
        );
        this.creationGuardService = Objects.requireNonNull(
                creationGuardService,
                "CommunityPostCreationGuardService cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate,
                "TransactionTemplate cannot be null."
        );
    }

    /**
     * Executes post creation with an optional image attachment.
     * Guaranteed: anti-spam checks occur under DB serialization lock BEFORE any binary upload.
     * If upload succeeds but subsequent post/event persistence or commit fails, uploaded asset is compensated.
     *
     * @param actorUserId the authenticated author's UUID
     * @param caption the post caption text
     * @param imageContent optional image stream, or null for caption-only
     * @param imageSizeBytes declared size in bytes of the image
     * @param imageContentType declared MIME type of the image
     * @param imageOriginalFilename original filename of the image
     * @return the newly created and persisted {@link CommunityPost}
     */
    public CommunityPost execute(
            UUID actorUserId,
            String caption,
            InputStream imageContent,
            long imageSizeBytes,
            String imageContentType,
            String imageOriginalFilename
    ) {
        Objects.requireNonNull(actorUserId, "Actor user ID cannot be null.");

        // 1. Fast-fail caption validation before acquiring DB lock or binary I/O
        String normalizedCaption = CommunityPost.validateAndNormalizeCaption(caption);

        UUID[] uploadedAssetHolder = new UUID[1];
        try {
            return transactionTemplate.execute(status -> {
                // 2. Acquire per-author write lock inside active transaction
                creationGuardService.acquireAuthorLock(actorUserId);

                // 3. Evaluate anti-spam guard rules under author lock: throws RateLimitException BEFORE upload if ineligible
                Instant now = clockPort.now();
                creationGuardService.evaluateEligibility(actorUserId, normalizedCaption, now);

                // 4. Upload image only after protected eligibility check succeeds
                UUID imageMediaAssetId = null;
                if (imageContent != null) {
                    imageMediaAssetId = imageUploadUseCase.uploadImage(
                            imageContent,
                            imageSizeBytes,
                            imageContentType,
                            imageOriginalFilename
                    );
                    uploadedAssetHolder[0] = imageMediaAssetId;
                }

                // 5. Create and persist post and append creation event (joins current transaction)
                CreateCommunityPostCommand command = new CreateCommunityPostCommand(
                        actorUserId,
                        normalizedCaption,
                        imageMediaAssetId
                );
                return createCommunityPostUseCase.execute(command);
            });
        } catch (RuntimeException postCreationEx) {
            if (uploadedAssetHolder[0] != null) {
                imageUploadUseCase.compensateUpload(uploadedAssetHolder[0], postCreationEx);
            }
            throw postCreationEx;
        }
    }
}
