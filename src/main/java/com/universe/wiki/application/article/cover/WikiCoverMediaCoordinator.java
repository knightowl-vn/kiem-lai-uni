package com.universe.wiki.application.article.cover;

import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.interfaces.MediaContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Narrow coordinator for interacting with the Media subsystem for Wiki cover assets.
 *
 * <p>Owns binary uploads, version replacements, best-effort variant generation,
 * and deletion/compensation operations without holding or managing Wiki database persistence.
 */
@Component
public class WikiCoverMediaCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiCoverMediaCoordinator.class);

    private static final int COVER_IMAGE_VARIANT_WIDTH = 300;

    public static final String WIKI_ARTICLE_COVER_CLIENT_TAG = "wiki.article.cover";

    private final MediaContract mediaContract;

    public WikiCoverMediaCoordinator(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    public UUID uploadInitialCover(WikiCoverUpload upload) {
        Objects.requireNonNull(upload, "WikiCoverUpload cannot be null.");

        UploadMediaAssetRequestDTO uploadRequest = new UploadMediaAssetRequestDTO(
                upload.content(),
                upload.sizeBytes(),
                upload.contentType(),
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                upload.originalFilename(),
                WIKI_ARTICLE_COVER_CLIENT_TAG
        );

        UploadMediaAssetResponseDTO uploadResponse = mediaContract.uploadAsset(uploadRequest);
        UUID assetId = uploadResponse.assetId();

        generateCoverVariantBestEffort(assetId);

        return assetId;
    }

    public MediaVersionUploadOutcome replaceCoverVersion(UUID assetId, WikiCoverUpload upload) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        Objects.requireNonNull(upload, "WikiCoverUpload cannot be null.");

        UploadMediaAssetVersionRequestDTO uploadVersionRequest = new UploadMediaAssetVersionRequestDTO(
                assetId,
                upload.content(),
                upload.sizeBytes(),
                upload.contentType(),
                upload.originalFilename()
        );

        UploadMediaAssetVersionConditionalResponseDTO response =
                mediaContract.uploadVersionIfContentChanged(uploadVersionRequest);

        if (response.outcome() == MediaVersionUploadOutcome.VERSION_CREATED) {
            generateCoverVariantBestEffort(assetId);
        }

        return response.outcome();
    }

    public void generateCoverVariantBestEffort(UUID assetId) {
        if (assetId == null) {
            return;
        }

        try {
            mediaContract.generateImageVariant(
                    new GenerateImageVariantRequestDTO(assetId, COVER_IMAGE_VARIANT_WIDTH)
            );
        } catch (RuntimeException ex) {
            LOGGER.warn(
                    "Không thể tạo biến thể ảnh bìa w{} cho Media Asset [{}]: {}. Giữ nguyên ảnh gốc.",
                    COVER_IMAGE_VARIANT_WIDTH,
                    assetId,
                    ex.getMessage(),
                    ex
            );
        }
    }

    public void compensateInitialCover(UUID newAssetId, RuntimeException primaryException) {
        if (newAssetId == null) {
            return;
        }

        try {
            mediaContract.delete(newAssetId);
        } catch (RuntimeException compEx) {
            LOGGER.error(
                    "Không thể xóa đền bù Media Asset [{}] mới tạo sau lỗi lưu database Wiki.",
                    newAssetId,
                    compEx
            );
            primaryException.addSuppressed(compEx);
        }
    }
}
