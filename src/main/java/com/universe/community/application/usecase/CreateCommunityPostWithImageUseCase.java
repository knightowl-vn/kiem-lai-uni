package com.universe.community.application.usecase;

import com.universe.community.application.command.CreateCommunityPostCommand;
import com.universe.community.domain.CommunityPost;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Application orchestrator use case for creating a Community Post with an optional image upload and failure compensation.
 */
@Service
public class CreateCommunityPostWithImageUseCase {

    private final CreateCommunityPostUseCase createCommunityPostUseCase;
    private final CommunityPostImageUploadUseCase imageUploadUseCase;

    public CreateCommunityPostWithImageUseCase(
            CreateCommunityPostUseCase createCommunityPostUseCase,
            CommunityPostImageUploadUseCase imageUploadUseCase
    ) {
        this.createCommunityPostUseCase = Objects.requireNonNull(
                createCommunityPostUseCase,
                "CreateCommunityPostUseCase cannot be null."
        );
        this.imageUploadUseCase = Objects.requireNonNull(
                imageUploadUseCase,
                "CommunityPostImageUploadUseCase cannot be null."
        );
    }

    /**
     * Executes post creation with an optional image attachment.
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

        UUID imageMediaAssetId = null;
        if (imageContent != null) {
            imageMediaAssetId = imageUploadUseCase.uploadImage(
                    imageContent,
                    imageSizeBytes,
                    imageContentType,
                    imageOriginalFilename
            );
        }

        try {
            CreateCommunityPostCommand command = new CreateCommunityPostCommand(
                    actorUserId,
                    caption,
                    imageMediaAssetId
            );
            return createCommunityPostUseCase.execute(command);
        } catch (RuntimeException postCreationEx) {
            if (imageMediaAssetId != null) {
                imageUploadUseCase.compensateUpload(imageMediaAssetId, postCreationEx);
            }
            throw postCreationEx;
        }
    }
}
