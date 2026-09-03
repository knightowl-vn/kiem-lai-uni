package com.universe.media.entry.delivery;

import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.MediaImageVariantNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.variant.GetMediaImageVariantContentQuery;
import com.universe.media.application.variant.GetMediaImageVariantContentUseCase;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/media/assets")
public class MediaDeliveryController {

    private final GetMediaAssetContentUseCase getMediaAssetContentUseCase;
    private final GetMediaImageVariantContentUseCase getMediaImageVariantContentUseCase;

    public MediaDeliveryController(
            GetMediaAssetContentUseCase getMediaAssetContentUseCase,
            GetMediaImageVariantContentUseCase getMediaImageVariantContentUseCase
    ) {
        this.getMediaAssetContentUseCase = Objects.requireNonNull(
                getMediaAssetContentUseCase,
                "GetMediaAssetContentUseCase cannot be null."
        );
        this.getMediaImageVariantContentUseCase = Objects.requireNonNull(
                getMediaImageVariantContentUseCase,
                "GetMediaImageVariantContentUseCase cannot be null."
        );
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<StreamingResponseBody> deliverAssetContent(
            @PathVariable UUID assetId,
            WebRequest request
    ) {
        GetMediaAssetContentResult result = getMediaAssetContentUseCase.execute(
                new GetMediaAssetContentQuery(assetId)
        );
        return streamContentResponse(result, request);
    }

    @GetMapping("/{assetId}/variants/{variantKey}")
    public ResponseEntity<StreamingResponseBody> deliverVariantContent(
            @PathVariable UUID assetId,
            @PathVariable String variantKey,
            WebRequest request
    ) {
        GetMediaAssetContentResult result = getMediaImageVariantContentUseCase.execute(
                new GetMediaImageVariantContentQuery(assetId, variantKey)
        );
        return streamContentResponse(result, request);
    }

    private ResponseEntity<StreamingResponseBody> streamContentResponse(
            GetMediaAssetContentResult result,
            WebRequest request
    ) {
        String eTag = "\"" + result.contentHash() + "\"";

        if (request.checkNotModified(eTag)) {
            try {
                result.content().close();
            } catch (IOException e) {
                throw new StorageException("Failed to close content stream on not-modified response", e);
            }
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, "public, no-cache")
                    .header(HttpHeaders.ETAG, eTag)
                    .build();
        }

        StreamingResponseBody streamingBody = outputStream -> {
            try (InputStream inputStream = result.content()) {
                inputStream.transferTo(outputStream);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, no-cache")
                .header(HttpHeaders.ETAG, eTag)
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .contentLength(result.sizeBytes())
                .body(streamingBody);
    }

    @ExceptionHandler({
            MediaAssetNotFoundException.class,
            MediaAssetVersionNotFoundException.class,
            MediaImageVariantNotFoundException.class,
            StorageObjectNotFoundException.class
    })
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
