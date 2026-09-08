package com.universe.novel.application.narration;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * Uploads one H.9D2 MP3 as a new public chapter playback Media asset.
 *
 * <p>The caller retains ownership of the encoded resource. This use case owns and closes only
 * the stream it opens from that resource.</p>
 *
 * <p><strong>Transaction boundary:</strong> non-transactional because Media upload is external I/O.</p>
 */
@Service
public class UploadChapterNarrationPlaybackMediaUseCase {

    private static final Logger log = LoggerFactory.getLogger(UploadChapterNarrationPlaybackMediaUseCase.class);
    private static final String MP3_MIME_TYPE = "audio/mpeg";

    private final MediaContract mediaContract;

    public UploadChapterNarrationPlaybackMediaUseCase(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "mediaContract must not be null");
    }

    public UploadChapterNarrationPlaybackMediaResult execute(
            UploadChapterNarrationPlaybackMediaCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        ChapterAudioEncodedResource resource = command.resource();
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        requireMp3(resource);
        long sizeBytes = requirePositiveSize(resource);
        String originalFilename = requireOriginalFilename(command.originalFilename());

        InputStream content = openStream(resource);
        UUID mediaAssetId;
        try {
            UploadMediaAssetResponseDTO response = mediaContract.uploadAsset(
                    new UploadMediaAssetRequestDTO(
                            content,
                            sizeBytes,
                            MP3_MIME_TYPE,
                            MediaTypeDTO.AUDIO,
                            MediaVisibilityDTO.PUBLIC,
                            originalFilename
                    )
            );
            mediaAssetId = requireMediaAssetId(response);
        } catch (RuntimeException | Error primaryFailure) {
            closePreservingPrimaryFailure(content, primaryFailure);
            throw primaryFailure;
        }

        closeAfterSuccessfulUpload(content, mediaAssetId);
        return new UploadChapterNarrationPlaybackMediaResult(mediaAssetId);
    }

    private static void requireMp3(ChapterAudioEncodedResource resource) {
        if (!MP3_MIME_TYPE.equals(resource.mimeType())) {
            throw new IllegalArgumentException(
                    "Chapter playback encoded resource MIME type must be audio/mpeg."
            );
        }
    }

    private static long requirePositiveSize(ChapterAudioEncodedResource resource) {
        long sizeBytes = resource.sizeBytes();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Chapter playback encoded resource sizeBytes must be positive.");
        }
        return sizeBytes;
    }

    private static String requireOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("originalFilename must not be blank");
        }
        return originalFilename;
    }

    private static InputStream openStream(ChapterAudioEncodedResource resource) {
        InputStream content = resource.openStream();
        if (content == null) {
            throw new IllegalStateException("ChapterAudioEncodedResource returned a null stream.");
        }
        return content;
    }

    private static UUID requireMediaAssetId(UploadMediaAssetResponseDTO response) {
        if (response == null) {
            throw new IllegalStateException("Media upload returned a null response.");
        }
        if (response.assetId() == null) {
            throw new IllegalStateException("Media upload returned a null asset ID.");
        }
        return response.assetId();
    }

    private static void closePreservingPrimaryFailure(InputStream content, Throwable primaryFailure) {
        try {
            content.close();
        } catch (IOException | RuntimeException closeFailure) {
            primaryFailure.addSuppressed(closeFailure);
        }
    }

    private static void closeAfterSuccessfulUpload(InputStream content, UUID mediaAssetId) {
        try {
            content.close();
        } catch (IOException | RuntimeException closeFailure) {
            log.warn(
                    "Media asset [{}] was created, but closing its chapter playback upload stream failed. "
                            + "Candidate identity is preserved.",
                    mediaAssetId,
                    closeFailure
            );
        }
    }
}
