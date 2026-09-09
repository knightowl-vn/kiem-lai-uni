package com.universe.media.entry.delivery;

import com.universe.media.application.asset.GetMediaAssetContentMetadataResult;
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
import com.universe.media.domain.StorageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MediaDeliveryControllerTest {

    private static final UUID ASSET_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String HASH =
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

    private static final byte[] PAYLOAD = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    @Mock
    private GetMediaAssetContentUseCase getMediaAssetContentUseCase;

    @Mock
    private GetMediaImageVariantContentUseCase getMediaImageVariantContentUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MediaDeliveryController controller = new MediaDeliveryController(
                getMediaAssetContentUseCase,
                getMediaImageVariantContentUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("full GET returns 200 with exact streamed bytes and representation headers")
    void shouldDeliverFullMediaAssetContent() throws Exception {
        GetMediaAssetContentMetadataResult metadata = metadata();
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream stream = trackingStream(PAYLOAD, closed);
        when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .thenReturn(metadata);
        when(getMediaAssetContentUseCase.open(metadata)).thenReturn(stream);

        MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, quotedETag()))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PAYLOAD));

        assertThat(closed).isTrue();
        verify(getMediaAssetContentUseCase).open(metadata);
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("closed byte range returns 206 with inclusive exact bytes")
    void shouldDeliverClosedByteRange() throws Exception {
        assertPartialRange("bytes=2-5", 2, 5, new byte[]{2, 3, 4, 5});
    }

    @Test
    @DisplayName("open-ended byte range returns start through EOF")
    void shouldDeliverOpenEndedByteRange() throws Exception {
        assertPartialRange("bytes=6-", 6, 9, new byte[]{6, 7, 8, 9});
    }

    @Test
    @DisplayName("suffix byte range returns final bytes")
    void shouldDeliverSuffixByteRange() throws Exception {
        assertPartialRange("bytes=-3", 7, 9, new byte[]{7, 8, 9});
    }

    @Test
    @DisplayName("oversized closed-range end is clamped to EOF")
    void shouldClampOversizedRangeEnd() throws Exception {
        assertPartialRange("bytes=7-999999999999999999999999", 7, 9, new byte[]{7, 8, 9});
    }

    @Test
    @DisplayName("oversized suffix is capped at the full representation")
    void shouldCapOversizedSuffixAtFullSize() throws Exception {
        assertPartialRange("bytes=-999999999999999999999999", 0, 9, PAYLOAD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=", "bytes=abc", "bytes=5-4", "bytes=10-", "bytes=-0", "bytes=1-2-3"})
    @DisplayName("malformed or unsatisfiable single byte range returns 416 without opening storage")
    void shouldRejectMalformedOrUnsatisfiableSingleRange(String rangeHeader) throws Exception {
        stubMetadata();

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.RANGE, rangeHeader))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.ETAG, quotedETag()));

        verify(getMediaAssetContentUseCase, never()).open(any());
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("multiple byte ranges are ignored and return the complete 200 representation")
    void shouldIgnoreMultipleRanges() throws Exception {
        assertIgnoredRangeReturnsFull("bytes=0-1,4-5");
    }

    @Test
    @DisplayName("unsupported range unit is ignored and returns the complete 200 representation")
    void shouldIgnoreUnsupportedRangeUnit() throws Exception {
        assertIgnoredRangeReturnsFull("items=0-2");
    }

    @Test
    @DisplayName("matching If-None-Match returns 304 without opening storage")
    void shouldReturn304WithoutOpeningStorage() throws Exception {
        stubMetadata();

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, quotedETag())
                        .header(HttpHeaders.RANGE, "bytes=2-4"))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, quotedETag()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));

        verify(getMediaAssetContentUseCase, never()).open(any());
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("matching strong If-Range applies the byte range")
    void shouldApplyRangeForMatchingStrongIfRange() throws Exception {
        GetMediaAssetContentMetadataResult metadata = stubMetadata();
        when(getMediaAssetContentUseCase.openRange(metadata, 1, 3))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.RANGE, "bytes=1-3")
                        .header(HttpHeaders.IF_RANGE, quotedETag()))
                .andExpect(status().isPartialContent())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));

        verify(getMediaAssetContentUseCase).openRange(metadata, 1, 3);
        verify(getMediaAssetContentUseCase, never()).open(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff\"",
            "W/\"a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e\"",
            "Wed, 21 Oct 2015 07:28:00 GMT"
    })
    @DisplayName("mismatched, weak, or date If-Range ignores Range and returns full 200")
    void shouldIgnoreRangeForUnsupportedIfRange(String ifRange) throws Exception {
        GetMediaAssetContentMetadataResult metadata = stubMetadata();
        when(getMediaAssetContentUseCase.open(metadata)).thenReturn(new ByteArrayInputStream(PAYLOAD));

        MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.RANGE, "bytes=1-3")
                        .header(HttpHeaders.IF_RANGE, ifRange))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().bytes(PAYLOAD));

        verify(getMediaAssetContentUseCase).open(metadata);
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("HEAD returns full metadata and never opens binary content")
    void shouldReturnHeadMetadataWithoutOpeningStorage() throws Exception {
        stubMetadata();

        mockMvc.perform(MockMvcRequestBuilders.request(
                        HttpMethod.HEAD,
                        "/media/assets/{assetId}/content",
                        ASSET_ID
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                .andExpect(header().string(HttpHeaders.ETAG, quotedETag()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().bytes(new byte[0]));

        verify(getMediaAssetContentUseCase, never()).open(any());
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("asset metadata not found returns 404")
    void shouldReturn404WhenAssetNotFound() throws Exception {
        when(getMediaAssetContentUseCase.resolveMetadata(any()))
                .thenThrow(new MediaAssetNotFoundException(ASSET_ID));

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("current version metadata not found returns 404")
    void shouldReturn404WhenVersionNotFound() throws Exception {
        when(getMediaAssetContentUseCase.resolveMetadata(any()))
                .thenThrow(new MediaAssetVersionNotFoundException(ASSET_ID, 1));

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("missing physical object during the selected open returns 404")
    void shouldReturn404WhenStorageObjectNotFound() throws Exception {
        GetMediaAssetContentMetadataResult metadata = stubMetadata();
        when(getMediaAssetContentUseCase.open(metadata))
                .thenThrow(new StorageObjectNotFoundException(StorageKey.of("objects/missing")));

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("provider mismatch during metadata resolution propagates StorageException")
    void shouldPropagateStorageExceptionWhenProviderMismatch() {
        when(getMediaAssetContentUseCase.resolveMetadata(any()))
                .thenThrow(new StorageException("Storage provider mismatch"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
        )).hasCauseInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("image variant GET remains a full streamed 200 response")
    void shouldKeepImageVariantDeliveryUnchanged() throws Exception {
        byte[] payload = new byte[]{10, 20, 30, 40};
        GetMediaAssetContentResult result = new GetMediaAssetContentResult(
                new ByteArrayInputStream(payload),
                payload.length,
                "image/jpeg",
                HASH
        );
        when(getMediaImageVariantContentUseCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w300")))
                .thenReturn(result);

        MvcResult mvcResult = mockMvc.perform(
                        get("/media/assets/{assetId}/variants/{variantKey}", ASSET_ID, "w300")
                )
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, quotedETag()))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "4"))
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().bytes(payload));
    }

    @Test
    @DisplayName("image variant not found behavior remains 404")
    void shouldKeepImageVariantNotFoundBehavior() throws Exception {
        when(getMediaImageVariantContentUseCase.execute(any()))
                .thenThrow(new MediaImageVariantNotFoundException(ASSET_ID, 1, "w300"));

        mockMvc.perform(get("/media/assets/{assetId}/variants/{variantKey}", ASSET_ID, "w300"))
                .andExpect(status().isNotFound());
    }

    private void assertPartialRange(
            String rangeHeader,
            long startInclusive,
            long endInclusive,
            byte[] expected
    ) throws Exception {
        GetMediaAssetContentMetadataResult metadata = stubMetadata();
        long length = endInclusive - startInclusive + 1;
        when(getMediaAssetContentUseCase.openRange(metadata, startInclusive, length))
                .thenReturn(new ByteArrayInputStream(expected));

        MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.RANGE, rangeHeader))
                .andExpect(status().isPartialContent())
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, quotedETag()))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes " + startInclusive + "-" + endInclusive + "/10"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(length)))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(expected));

        verify(getMediaAssetContentUseCase).openRange(metadata, startInclusive, length);
        verify(getMediaAssetContentUseCase, never()).open(any());
    }

    private void assertIgnoredRangeReturnsFull(String rangeHeader) throws Exception {
        GetMediaAssetContentMetadataResult metadata = stubMetadata();
        when(getMediaAssetContentUseCase.open(metadata)).thenReturn(new ByteArrayInputStream(PAYLOAD));

        MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.RANGE, rangeHeader))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().bytes(PAYLOAD));

        verify(getMediaAssetContentUseCase).open(metadata);
        verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
    }

    private GetMediaAssetContentMetadataResult stubMetadata() {
        GetMediaAssetContentMetadataResult metadata = metadata();
        when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .thenReturn(metadata);
        return metadata;
    }

    private GetMediaAssetContentMetadataResult metadata() {
        return new GetMediaAssetContentMetadataResult(
                ASSET_ID,
                1,
                PAYLOAD.length,
                "audio/mpeg",
                HASH
        );
    }

    private InputStream trackingStream(byte[] payload, AtomicBoolean closed) {
        return new ByteArrayInputStream(payload) {
            @Override
            public void close() throws IOException {
                super.close();
                closed.set(true);
            }
        };
    }

    private String quotedETag() {
        return "\"" + HASH + "\"";
    }
}
