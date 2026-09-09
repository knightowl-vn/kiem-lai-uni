package com.universe.media.entry.delivery;

import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentMetadataResult;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.MediaImageVariantNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.variant.GetMediaImageVariantContentQuery;
import com.universe.media.application.variant.GetMediaImageVariantContentUseCase;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/media/assets")
public class MediaDeliveryController {

    private static final String CACHE_CONTROL = "public, no-cache";
    private static final String BYTE_RANGE_UNIT = "bytes";

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

    @RequestMapping(
            value = "/{assetId}/content",
            method = {RequestMethod.GET, RequestMethod.HEAD}
    )
    public ResponseEntity<StreamingResponseBody> deliverAssetContent(
            @PathVariable UUID assetId,
            HttpServletRequest servletRequest,
            WebRequest webRequest
    ) {
        GetMediaAssetContentMetadataResult metadata = getMediaAssetContentUseCase.resolveMetadata(
                new GetMediaAssetContentQuery(assetId)
        );
        String eTag = quotedETag(metadata.contentHash());

        if (webRequest.checkNotModified(eTag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .header(HttpHeaders.ETAG, eTag)
                    .header(HttpHeaders.ACCEPT_RANGES, BYTE_RANGE_UNIT)
                    .build();
        }

        if (RequestMethod.HEAD.name().equals(servletRequest.getMethod())) {
            return contentResponseBuilder(HttpStatus.OK, metadata, eTag)
                    .contentLength(metadata.sizeBytes())
                    .build();
        }

        RangeResolution range = resolveRange(
                servletRequest.getHeader(HttpHeaders.RANGE),
                metadata.sizeBytes()
        );
        if (range.status() == RangeStatus.UNSATISFIABLE) {
            return contentResponseBuilder(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, metadata, eTag)
                    .header(HttpHeaders.CONTENT_RANGE, BYTE_RANGE_UNIT + " */" + metadata.sizeBytes())
                    .build();
        }

        if (range.status() == RangeStatus.PARTIAL
                && allowsRange(servletRequest.getHeader(HttpHeaders.IF_RANGE), eTag)) {
            InputStream content = getMediaAssetContentUseCase.openRange(
                    metadata,
                    range.startInclusive(),
                    range.length()
            );
            return streamingResponse(
                    HttpStatus.PARTIAL_CONTENT,
                    metadata,
                    eTag,
                    content,
                    range.length(),
                    BYTE_RANGE_UNIT + " " + range.startInclusive() + "-" + range.endInclusive()
                            + "/" + metadata.sizeBytes()
            );
        }

        InputStream content = getMediaAssetContentUseCase.open(metadata);
        return streamingResponse(
                HttpStatus.OK,
                metadata,
                eTag,
                content,
                metadata.sizeBytes(),
                null
        );
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
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .header(HttpHeaders.ETAG, eTag)
                    .build();
        }

        StreamingResponseBody streamingBody = outputStream -> {
            try (InputStream inputStream = result.content()) {
                inputStream.transferTo(outputStream);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.ETAG, eTag)
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .contentLength(result.sizeBytes())
                .body(streamingBody);
    }

    private ResponseEntity<StreamingResponseBody> streamingResponse(
            HttpStatus status,
            GetMediaAssetContentMetadataResult metadata,
            String eTag,
            InputStream content,
            long contentLength,
            String contentRange
    ) {
        StreamingResponseBody streamingBody = outputStream -> {
            try (InputStream inputStream = content) {
                inputStream.transferTo(outputStream);
            }
        };

        ResponseEntity.BodyBuilder builder = contentResponseBuilder(status, metadata, eTag)
                .contentLength(contentLength);
        if (contentRange != null) {
            builder.header(HttpHeaders.CONTENT_RANGE, contentRange);
        }
        return builder.body(streamingBody);
    }

    private ResponseEntity.BodyBuilder contentResponseBuilder(
            HttpStatus status,
            GetMediaAssetContentMetadataResult metadata,
            String eTag
    ) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.ETAG, eTag)
                .header(HttpHeaders.ACCEPT_RANGES, BYTE_RANGE_UNIT)
                .contentType(MediaType.parseMediaType(metadata.mimeType()));
    }

    private String quotedETag(String contentHash) {
        return "\"" + contentHash + "\"";
    }

    private boolean allowsRange(String ifRange, String currentETag) {
        return ifRange == null || ifRange.trim().equals(currentETag);
    }

    private RangeResolution resolveRange(String rangeHeader, long fullSize) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return RangeResolution.ignored();
        }

        String value = rangeHeader.trim();
        int equalsIndex = value.indexOf('=');
        if (equalsIndex < 0) {
            return BYTE_RANGE_UNIT.equalsIgnoreCase(value)
                    ? RangeResolution.unsatisfiable()
                    : RangeResolution.ignored();
        }

        String unit = value.substring(0, equalsIndex).trim();
        if (!BYTE_RANGE_UNIT.equalsIgnoreCase(unit)) {
            return RangeResolution.ignored();
        }

        String rangeSpec = value.substring(equalsIndex + 1).trim();
        if (rangeSpec.indexOf(',') >= 0) {
            return RangeResolution.ignored();
        }
        if (fullSize == 0) {
            return RangeResolution.unsatisfiable();
        }

        int hyphenIndex = rangeSpec.indexOf('-');
        if (hyphenIndex < 0 || hyphenIndex != rangeSpec.lastIndexOf('-')) {
            return RangeResolution.unsatisfiable();
        }

        String startPart = rangeSpec.substring(0, hyphenIndex);
        String endPart = rangeSpec.substring(hyphenIndex + 1);
        try {
            if (startPart.isEmpty()) {
                long suffixLength = parseUnsignedLong(endPart);
                if (suffixLength <= 0) {
                    return RangeResolution.unsatisfiable();
                }
                long selectedLength = Math.min(suffixLength, fullSize);
                return RangeResolution.partial(fullSize - selectedLength, fullSize - 1);
            }

            long startInclusive = parseUnsignedLong(startPart);
            if (startInclusive >= fullSize) {
                return RangeResolution.unsatisfiable();
            }

            long endInclusive = endPart.isEmpty()
                    ? fullSize - 1
                    : parseUnsignedLong(endPart);
            if (endInclusive < startInclusive) {
                return RangeResolution.unsatisfiable();
            }
            return RangeResolution.partial(startInclusive, Math.min(endInclusive, fullSize - 1));
        } catch (NumberFormatException e) {
            return RangeResolution.unsatisfiable();
        }
    }

    private long parseUnsignedLong(String value) {
        if (value.isEmpty()) {
            throw new NumberFormatException("Empty byte position");
        }
        long result = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new NumberFormatException("Invalid byte position");
            }
            int digit = character - '0';
            if (result > (Long.MAX_VALUE - digit) / 10) {
                return Long.MAX_VALUE;
            }
            result = result * 10 + digit;
        }
        return result;
    }

    private enum RangeStatus {
        IGNORED,
        PARTIAL,
        UNSATISFIABLE
    }

    private record RangeResolution(
            RangeStatus status,
            long startInclusive,
            long endInclusive
    ) {

        private static RangeResolution ignored() {
            return new RangeResolution(RangeStatus.IGNORED, 0, 0);
        }

        private static RangeResolution partial(long startInclusive, long endInclusive) {
            return new RangeResolution(RangeStatus.PARTIAL, startInclusive, endInclusive);
        }

        private static RangeResolution unsatisfiable() {
            return new RangeResolution(RangeStatus.UNSATISFIABLE, 0, 0);
        }

        private long length() {
            return endInclusive - startInclusive + 1;
        }
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
