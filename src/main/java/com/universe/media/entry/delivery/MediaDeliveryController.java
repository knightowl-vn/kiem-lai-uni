package com.universe.media.entry.delivery;

import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;

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

    public MediaDeliveryController(
            GetMediaAssetContentUseCase getMediaAssetContentUseCase
    ) {
        this.getMediaAssetContentUseCase = Objects.requireNonNull(
                getMediaAssetContentUseCase,
                "GetMediaAssetContentUseCase cannot be null."
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

        String eTag = "\"" + result.contentHash() + "\"";

        if (request.checkNotModified(eTag)) {
            try {
                result.content().close();
            } catch (IOException ignored) {
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
            StorageObjectNotFoundException.class
    })
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
