package com.universe.screen.infrastructure.media;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class ScreenMediaValidationAdapter implements ScreenMediaValidationPort {

    private final MediaContract mediaContract;

    public ScreenMediaValidationAdapter(MediaContract mediaContract) {
        this.mediaContract = Objects.requireNonNull(mediaContract, "MediaContract cannot be null.");
    }

    @Override
    public void validateImageMediaAsset(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        Optional<MediaAssetDetailDTO> detailOpt = mediaContract.getAssetDetail(mediaAssetId);
        if (detailOpt.isEmpty()) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        MediaAssetDetailDTO detail = detailOpt.get();
        if (detail.status() == MediaAssetStatusDTO.DELETED) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.IMAGE) {
            throw new InvalidScreenMediaAssetTypeException(mediaAssetId, String.valueOf(detail.mediaType()));
        }
    }

    @Override
    public void validateVideoMediaAsset(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        Optional<MediaAssetDetailDTO> detailOpt = mediaContract.getAssetDetail(mediaAssetId);
        if (detailOpt.isEmpty()) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        MediaAssetDetailDTO detail = detailOpt.get();
        if (detail.status() == MediaAssetStatusDTO.DELETED) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.VIDEO) {
            throw new InvalidScreenMediaAssetTypeException(mediaAssetId, "VIDEO", String.valueOf(detail.mediaType()));
        }
    }

    @Override
    public void lockAndValidateVideoMediaAsset(UUID mediaAssetId) {
        if (mediaAssetId == null) {
            return;
        }

        MediaAssetDetailDTO detail;
        try {
            detail = mediaContract.lockAndValidateActiveAsset(mediaAssetId, null);
        } catch (MediaAssetNotFoundException e) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail == null || detail.status() == MediaAssetStatusDTO.DELETED) {
            throw new ScreenMediaAssetNotFoundException(mediaAssetId);
        }

        if (detail.mediaType() != MediaTypeDTO.VIDEO) {
            throw new InvalidScreenMediaAssetTypeException(mediaAssetId, "VIDEO", String.valueOf(detail.mediaType()));
        }
    }
}
