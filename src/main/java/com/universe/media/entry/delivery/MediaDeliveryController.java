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
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;

@Controller
@RequestMapping("/media/assets")
public class MediaDeliveryController {

    private static final Logger log = LoggerFactory.getLogger(MediaDeliveryController.class);

    public static final long MEDIA_STREAMING_ASYNC_TIMEOUT_MS = 1_800_000L; // 30 minutes scoped streaming timeout
    private static final int STREAM_BUFFER_SIZE = 32768; // 32 KiB standard streaming buffer
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
        if (servletRequest != null) {
            WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(servletRequest);
            if (asyncManager != null && asyncManager.getAsyncWebRequest() != null) {
                asyncManager.getAsyncWebRequest().setTimeout(MEDIA_STREAMING_ASYNC_TIMEOUT_MS);
            }
        }

        GetMediaAssetContentMetadataResult metadata = getMediaAssetContentUseCase.resolveMetadata(
                new GetMediaAssetContentQuery(assetId)
        );

        if (metadata.isRedirect()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(metadata.redirectUri())
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

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

        String rangeHeader = servletRequest.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && !rangeHeader.isBlank()
                && !allowsRange(servletRequest.getHeader(HttpHeaders.IF_RANGE), eTag)) {
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

        RangeResolution range = resolveRange(
                rangeHeader,
                metadata.sizeBytes()
        );
        if (range.status() == RangeStatus.UNSATISFIABLE) {
            return contentResponseBuilder(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, metadata, eTag)
                    .header(HttpHeaders.CONTENT_RANGE, BYTE_RANGE_UNIT + " */" + metadata.sizeBytes())
                    .build();
        }

        if (range.status() == RangeStatus.PARTIAL) {
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

        if (result.isRedirect()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(result.redirectUri())
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        return streamContentResponse(assetId, result, request);
    }

    private ResponseEntity<StreamingResponseBody> streamContentResponse(
            UUID assetId,
            GetMediaAssetContentResult result,
            WebRequest request
    ) {
        String eTag = "\"" + result.contentHash() + "\"";

        if (request.checkNotModified(eTag)) {
            if (result.content() != null) {
                try {
                    result.content().close();
                } catch (IOException e) {
                    throw new StorageException("Failed to close content stream on not-modified response", e);
                }
            }
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .header(HttpHeaders.ETAG, eTag)
                    .build();
        }

        StreamingResponseBody streamingBody = outputStream -> {
            if (result.content() != null) {
                copyStream(
                        assetId,
                        result.content(),
                        outputStream,
                        result.sizeBytes()
                );
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
        StreamingResponseBody streamingBody = outputStream -> copyStream(
                metadata.assetId(),
                content,
                outputStream,
                contentLength
        );

        ResponseEntity.BodyBuilder builder = contentResponseBuilder(status, metadata, eTag)
                .contentLength(contentLength);
        if (contentRange != null) {
            builder.header(HttpHeaders.CONTENT_RANGE, contentRange);
        }
        return builder.body(streamingBody);
    }

    private void copyStream(
            UUID assetId,
            InputStream inputStream,
            java.io.OutputStream outputStream,
            long expectedLength
    ) throws IOException {
        if (inputStream == null) {
            log.error("Storage stream is null for asset {}", assetId);
            throw new StorageStreamException("Storage stream is null", null);
        }

        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        long transferred = 0;
        try (inputStream) {
            while (true) {
                int bytesRead;
                try {
                    bytesRead = inputStream.read(buffer);
                } catch (IOException readEx) {
                    log.error("Storage stream read failure for asset {}: {}",
                            assetId, readEx.getMessage(), readEx);
                    throw new StorageStreamException("Storage stream read failure", readEx);
                } catch (RuntimeException readRuntimeEx) {
                    log.error("Storage stream runtime read failure for asset {}: {}",
                            assetId, readRuntimeEx.getMessage(), readRuntimeEx);
                    throw new StorageStreamException("Storage stream runtime read failure", readRuntimeEx);
                }

                if (bytesRead == -1) {
                    break;
                }

                try {
                    outputStream.write(buffer, 0, bytesRead);
                    transferred += bytesRead;
                } catch (IOException writeEx) {
                    if (isClientDisconnect(writeEx)) {
                        log.debug("Client disconnected while writing media stream for asset {}: {}",
                                assetId, writeEx.getMessage());
                        return;
                    }
                    log.warn("Downstream client write failure for asset {}: {}",
                            assetId, writeEx.getMessage());
                    throw writeEx;
                }
            }

            if (transferred < expectedLength) {
                log.error("Premature upstream EOF for asset {}: expected {} bytes, transferred {} bytes",
                        assetId, expectedLength, transferred);
                throw new StorageStreamException(
                        "Premature upstream EOF: transferred " + transferred + " of " + expectedLength + " bytes",
                        null
                );
            }
        }
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

    /**
     * Exception indicating an I/O failure from the upstream storage provider (reading stream or premature EOF),
     * which must not be misclassified as a downstream client disconnect.
     */
    public static class StorageStreamException extends IOException {
        public StorageStreamException(String message, Throwable cause) {
            super(message + (cause != null && cause.getMessage() != null ? ": " + cause.getMessage() : ""), cause);
        }
    }

    public static boolean isUpstreamStorageFailure(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof StorageStreamException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean isClientDisconnect(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String className = current.getClass().getName();
            String msg = (current.getMessage() != null) ? current.getMessage().toLowerCase() : "";
            if (className.endsWith("ClientAbortException")
                    || msg.contains("broken pipe")
                    || msg.contains("connection reset")
                    || msg.contains("connection was aborted")
                    || msg.contains("an established connection was aborted")
                    || msg.contains("software caused connection abort")
                    || msg.contains("socket write error")
                    || msg.contains("connection closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(
            AsyncRequestTimeoutException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (response != null && response.isCommitted()) {
            log.debug("Async request timeout after response commit for {}: {}",
                    request != null ? request.getRequestURI() : "unknown", ex.getMessage());
            return null;
        }
        log.warn("Async request timeout before response commit for {}: {}",
                request != null ? request.getRequestURI() : "unknown", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(StorageStreamException.class)
    public ResponseEntity<Void> handleStorageStreamException(
            StorageStreamException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws StorageStreamException {
        if (response != null && response.isCommitted()) {
            log.error("Storage stream failure after response commit for {}: {}",
                    request != null ? request.getRequestURI() : "unknown", ex.getMessage(), ex);
            return null;
        }
        log.error("Storage stream failure for {}: {}",
                request != null ? request.getRequestURI() : "unknown", ex.getMessage(), ex);
        throw ex;
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleIOException(
            IOException ex,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected for {}: {}",
                    request != null ? request.getRequestURI() : "unknown", ex.getMessage());
            return null;
        }
        if (response != null && response.isCommitted()) {
            log.error("I/O failure after response commit for {}: {}",
                    request != null ? request.getRequestURI() : "unknown", ex.getMessage(), ex);
            return null;
        }
        throw ex;
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
