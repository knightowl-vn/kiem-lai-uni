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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private MediaDeliveryController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new MediaDeliveryController(
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
    @DisplayName("redirect asset GET returns 302 Found with Location header to publicUrl")
    void shouldDeliverAssetContentRedirect() throws Exception {
        GetMediaAssetContentMetadataResult redirectMetadata = new GetMediaAssetContentMetadataResult(
                ASSET_ID,
                1,
                1024L,
                "image/webp",
                HASH,
                "https://res.cloudinary.com/test/image/upload/v1/kiemlai/covers/cover1.webp"
        );
        when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(ASSET_ID)))
                .thenReturn(redirectMetadata);

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://res.cloudinary.com/test/image/upload/v1/kiemlai/covers/cover1.webp"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"));

        verify(getMediaAssetContentUseCase, never()).open(any());
    }

    @Test
    @DisplayName("redirect variant GET returns 302 Found with Location header to publicUrl")
    void shouldDeliverVariantContentRedirect() throws Exception {
        GetMediaAssetContentResult redirectResult = GetMediaAssetContentResult.redirect(
                "https://res.cloudinary.com/test/image/upload/c_scale,w_400/kiemlai/covers/cover1.webp",
                1024L,
                "image/webp",
                HASH
        );
        when(getMediaImageVariantContentUseCase.execute(new GetMediaImageVariantContentQuery(ASSET_ID, "w400")))
                .thenReturn(redirectResult);

        mockMvc.perform(get("/media/assets/{assetId}/variants/{variantKey}", ASSET_ID, "w400"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://res.cloudinary.com/test/image/upload/c_scale,w_400/kiemlai/covers/cover1.webp"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"));
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
        GetMediaAssetContentResult result = GetMediaAssetContentResult.stream(
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

    @org.junit.jupiter.api.Nested
    @DisplayName("Video HTTP Range Delivery Tests (MS-08C3 #75 Matrix)")
    class VideoRangeDeliveryTests {

        private static final UUID VIDEO_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        private static final String VIDEO_MIME = "video/mp4";
        private static final String VIDEO_HASH =
                "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3";
        private static final byte[] VIDEO_PAYLOAD = new byte[100];

        static {
            for (int i = 0; i < VIDEO_PAYLOAD.length; i++) {
                VIDEO_PAYLOAD[i] = (byte) (i + 1);
            }
        }

        private GetMediaAssetContentMetadataResult videoMetadata() {
            return new GetMediaAssetContentMetadataResult(
                    VIDEO_ID,
                    1,
                    VIDEO_PAYLOAD.length,
                    VIDEO_MIME,
                    VIDEO_HASH,
                    null
            );
        }

        private String videoETag() {
            return "\"" + VIDEO_HASH + "\"";
        }

        private void stubVideoMetadata() {
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(videoMetadata());
        }

        @Test
        @DisplayName("A. Full VIDEO request: returns 200 with video/mp4, Content-Length, streaming bytes, and closes stream")
        void shouldDeliverFullVideoContent() throws Exception {
            stubVideoMetadata();
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream stream = trackingStream(VIDEO_PAYLOAD, streamClosed);
            when(getMediaAssetContentUseCase.open(videoMetadata())).thenReturn(stream);

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andExpect(header().string(HttpHeaders.ETAG, videoETag()))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(VIDEO_PAYLOAD));

            assertThat(streamClosed.get()).isTrue();
            verify(getMediaAssetContentUseCase).open(videoMetadata());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("B. Valid byte range: returns 206 Partial Content with Content-Range, Content-Length, exact slice, and invokes openRange")
        void shouldDeliverValidVideoByteRange() throws Exception {
            stubVideoMetadata();
            byte[] expectedSlice = new byte[10];
            System.arraycopy(VIDEO_PAYLOAD, 0, expectedSlice, 0, 10);
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 0, 10))
                    .thenReturn(trackingStream(expectedSlice, streamClosed));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9"))
                    .andExpect(status().isPartialContent())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-9/100"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "10"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.ETAG, videoETag()))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isPartialContent())
                    .andExpect(content().bytes(expectedSlice));

            assertThat(streamClosed.get()).isTrue();
            verify(getMediaAssetContentUseCase).openRange(videoMetadata(), 0, 10);
            verify(getMediaAssetContentUseCase, never()).open(any());
        }

        @Test
        @DisplayName("C. Seek-style range: non-zero start offset returns 206 with correct partial bytes without whole-object fallback")
        void shouldDeliverSeekStyleRangeWithNonZeroStart() throws Exception {
            stubVideoMetadata();
            int start = 25;
            int length = 50;
            int end = start + length - 1; // 74
            byte[] expectedSlice = new byte[length];
            System.arraycopy(VIDEO_PAYLOAD, start, expectedSlice, 0, length);
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), start, length))
                    .thenReturn(trackingStream(expectedSlice, streamClosed));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=" + start + "-" + end))
                    .andExpect(status().isPartialContent())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/100"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(length)))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isPartialContent())
                    .andExpect(content().bytes(expectedSlice));

            assertThat(streamClosed.get()).isTrue();
            verify(getMediaAssetContentUseCase).openRange(videoMetadata(), start, length);
            verify(getMediaAssetContentUseCase, never()).open(any());
        }

        @Test
        @DisplayName("D. Repeated ranges: multiple independent seek-style range requests are isolated, reusable, and closed")
        void shouldHandleRepeatedSeekStyleRangesIndependently() throws Exception {
            stubVideoMetadata();

            // Request 1: bytes=10-29 (length 20)
            byte[] slice1 = new byte[20];
            System.arraycopy(VIDEO_PAYLOAD, 10, slice1, 0, 20);
            AtomicBoolean closed1 = new AtomicBoolean(false);
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 10, 20))
                    .thenReturn(trackingStream(slice1, closed1));

            MvcResult result1 = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=10-29"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-29/100"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "20"))
                    .andReturn();
            mockMvc.perform(asyncDispatch(result1))
                    .andExpect(content().bytes(slice1));
            assertThat(closed1.get()).isTrue();

            // Request 2: bytes=60-79 (length 20)
            byte[] slice2 = new byte[20];
            System.arraycopy(VIDEO_PAYLOAD, 60, slice2, 0, 20);
            AtomicBoolean closed2 = new AtomicBoolean(false);
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 60, 20))
                    .thenReturn(trackingStream(slice2, closed2));

            MvcResult result2 = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=60-79"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 60-79/100"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "20"))
                    .andReturn();
            mockMvc.perform(asyncDispatch(result2))
                    .andExpect(content().bytes(slice2));
            assertThat(closed2.get()).isTrue();
        }

        @Test
        @DisplayName("E. Open-ended range: bytes=60- delivers from offset to EOF with 206 Partial Content")
        void shouldDeliverOpenEndedRangeForVideo() throws Exception {
            stubVideoMetadata();
            int start = 60;
            int length = 40; // 60 to 99
            byte[] expectedSlice = new byte[length];
            System.arraycopy(VIDEO_PAYLOAD, start, expectedSlice, 0, length);
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), start, length))
                    .thenReturn(new ByteArrayInputStream(expectedSlice));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=60-"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 60-99/100"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "40"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(expectedSlice));

            verify(getMediaAssetContentUseCase).openRange(videoMetadata(), 60, 40);
        }

        @Test
        @DisplayName("E. Suffix range: bytes=-25 delivers final 25 bytes with 206 Partial Content")
        void shouldDeliverSuffixRangeForVideo() throws Exception {
            stubVideoMetadata();
            int start = 75;
            int length = 25; // 75 to 99
            byte[] expectedSlice = new byte[length];
            System.arraycopy(VIDEO_PAYLOAD, start, expectedSlice, 0, length);
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), start, length))
                    .thenReturn(new ByteArrayInputStream(expectedSlice));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=-25"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 75-99/100"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "25"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(expectedSlice));

            verify(getMediaAssetContentUseCase).openRange(videoMetadata(), 75, 25);
        }

        @Test
        @DisplayName("F. Malformed range: multi-range or unsupported unit ignores range and returns 200 full content")
        void shouldIgnoreMalformedMultiRangeAndDeliverFullContent() throws Exception {
            stubVideoMetadata();
            when(getMediaAssetContentUseCase.open(videoMetadata()))
                    .thenReturn(new ByteArrayInputStream(VIDEO_PAYLOAD));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-10,20-30"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(VIDEO_PAYLOAD));

            verify(getMediaAssetContentUseCase).open(videoMetadata());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }

        @ParameterizedTest
        @ValueSource(strings = {"bytes=abc", "bytes=50-20", "bytes=100-", "bytes=-0", "bytes=1-2-3"})
        @DisplayName("G. Unsatisfiable range: returns 416 with Content-Range bytes */100 and never opens storage")
        void shouldRejectUnsatisfiableRangeWith416(String rangeHeader) throws Exception {
            stubVideoMetadata();

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, rangeHeader))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */100"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.ETAG, videoETag()));

            verify(getMediaAssetContentUseCase, never()).open(any());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("H. Missing asset: unknown asset ID returns 404")
        void shouldReturn404WhenVideoAssetNotFound() throws Exception {
            when(getMediaAssetContentUseCase.resolveMetadata(any()))
                    .thenThrow(new MediaAssetNotFoundException(VIDEO_ID));

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("I. Missing provider object: StorageObjectNotFoundException on openRange returns 404")
        void shouldReturn404WhenProviderObjectMissingOnRange() throws Exception {
            stubVideoMetadata();
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 0, 10))
                    .thenThrow(new StorageObjectNotFoundException(StorageKey.of("objects/missing-video.mp4")));

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("J. Provider range failure: StorageException on openRange propagates cleanly")
        void shouldPropagateStorageExceptionOnProviderRangeFailure() {
            stubVideoMetadata();
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 0, 10))
                    .thenThrow(new StorageException("B2 network failure"));

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9"))
            )).hasCauseInstanceOf(StorageException.class);
        }

        @Test
        @DisplayName("K. Partial read failure: stream read error still closes stream via try-with-resources")
        void shouldCloseStreamWhenPartialReadFails() throws Exception {
            stubVideoMetadata();
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream failingStream = new InputStream() {
                private int count = 0;

                @Override
                public int read() throws IOException {
                    if (count++ > 5) {
                        throw new IOException("Simulated network stream disconnect during video playback");
                    }
                    return 42;
                }

                @Override
                public void close() {
                    streamClosed.set(true);
                }
            };

            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 0, 50))
                    .thenReturn(failingStream);

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-49"))
                    .andExpect(status().isPartialContent())
                    .andReturn();

            org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> mockMvc.perform(asyncDispatch(result))
            );

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("L. MIME integrity: video delivery strictly uses video/mp4 from metadata without extension guessing")
        void shouldDeriveContentTypeStrictlyFromPersistedMetadata() throws Exception {
            stubVideoMetadata();
            when(getMediaAssetContentUseCase.open(videoMetadata()))
                    .thenReturn(new ByteArrayInputStream(VIDEO_PAYLOAD));

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"));
        }

        @Test
        @DisplayName("ETag / If-Range: matching strong If-Range serves byte range with 206")
        void shouldServeRangeWhenIfRangeMatchesStrongETag() throws Exception {
            stubVideoMetadata();
            byte[] slice = new byte[10];
            when(getMediaAssetContentUseCase.openRange(videoMetadata(), 0, 10))
                    .thenReturn(new ByteArrayInputStream(slice));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9")
                            .header(HttpHeaders.IF_RANGE, videoETag()))
                    .andExpect(status().isPartialContent())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(slice));

            verify(getMediaAssetContentUseCase).openRange(videoMetadata(), 0, 10);
            verify(getMediaAssetContentUseCase, never()).open(any());
        }

        @Test
        @DisplayName("ETag / If-Range: mismatched If-Range ignores range and returns 200 full content")
        void shouldServeFullContentWhenIfRangeDoesNotMatchETag() throws Exception {
            stubVideoMetadata();
            when(getMediaAssetContentUseCase.open(videoMetadata()))
                    .thenReturn(new ByteArrayInputStream(VIDEO_PAYLOAD));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9")
                            .header(HttpHeaders.IF_RANGE, "\"stale-hash\""))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(VIDEO_PAYLOAD));

            verify(getMediaAssetContentUseCase).open(videoMetadata());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("ETag / If-Range: mismatched If-Range + otherwise-unsatisfiable Range ignores Range and returns 200 full content")
        void shouldServeFullContentWhenIfRangeMismatchesEvenIfRangeIsUnsatisfiable() throws Exception {
            stubVideoMetadata();
            when(getMediaAssetContentUseCase.open(videoMetadata()))
                    .thenReturn(new ByteArrayInputStream(VIDEO_PAYLOAD));

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=200-300")
                            .header(HttpHeaders.IF_RANGE, "\"stale-hash\""))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(VIDEO_PAYLOAD));

            verify(getMediaAssetContentUseCase).open(videoMetadata());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("ETag / If-Range: matching strong If-Range + unsatisfiable Range returns 416 without opening storage")
        void shouldReturn416WhenIfRangeMatchesStrongETagAndRangeIsUnsatisfiable() throws Exception {
            stubVideoMetadata();

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.RANGE, "bytes=200-300")
                            .header(HttpHeaders.IF_RANGE, videoETag()))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */100"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.ETAG, videoETag()));

            verify(getMediaAssetContentUseCase, never()).open(any());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("ETag / If-None-Match: matching ETag returns 304 Not Modified without opening storage")
        void shouldReturn304WithoutOpeningStorageWhenIfNoneMatchMatches() throws Exception {
            stubVideoMetadata();

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ID)
                            .header(HttpHeaders.IF_NONE_MATCH, videoETag())
                            .header(HttpHeaders.RANGE, "bytes=0-9"))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string(HttpHeaders.ETAG, videoETag()))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));

            verify(getMediaAssetContentUseCase, never()).open(any());
            verify(getMediaAssetContentUseCase, never()).openRange(any(), anyLong(), anyLong());
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Public Video Streaming Async Timeout and Lifecycle Tests (MS-08G3)")
    class PublicStreamingAsyncAndLifecycleTests {

        private static final UUID VIDEO_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        private static final String VIDEO_MIME = "video/mp4";
        private static final String VIDEO_HASH =
                "e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3";
        private static final byte[] VIDEO_DATA = new byte[100];

        static {
            for (int i = 0; i < VIDEO_DATA.length; i++) {
                VIDEO_DATA[i] = (byte) (i + 1);
            }
        }

        private GetMediaAssetContentMetadataResult videoMetadata() {
            return new GetMediaAssetContentMetadataResult(
                    VIDEO_ID,
                    1,
                    VIDEO_DATA.length,
                    VIDEO_MIME,
                    VIDEO_HASH,
                    null
            );
        }

        @Test
        @DisplayName("1. Scoped Async Timeout: configures WebAsyncManager timeout to 30 minutes when AsyncWebRequest is present")
        void shouldConfigureAsyncTimeoutTo30Minutes() {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            AsyncWebRequest asyncWebRequest = org.mockito.Mockito.mock(AsyncWebRequest.class);
            WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
            asyncManager.setAsyncWebRequest(asyncWebRequest);

            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            assertThat(response).isNotNull();
            verify(asyncWebRequest).setTimeout(1_800_000L);
        }

        @Test
        @DisplayName("2. Async timeout before commit: returns 503 SERVICE_UNAVAILABLE")
        void shouldReturn503OnAsyncTimeoutBeforeCommit() {
            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AsyncRequestTimeoutException timeoutEx = new AsyncRequestTimeoutException();

            ResponseEntity<Void> result = controller.handleAsyncTimeout(timeoutEx, request, response);

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("3. Async timeout after commit: returns null without attempting to write error body")
        void shouldReturnNullOnAsyncTimeoutAfterCommit() {
            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setCommitted(true);
            AsyncRequestTimeoutException timeoutEx = new AsyncRequestTimeoutException();

            ResponseEntity<Void> result = controller.handleAsyncTimeout(timeoutEx, request, response);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("4. StorageStreamException identification: correctly identifies StorageStreamException in cause chain")
        void shouldIdentifyStorageStreamExceptionInCauseChain() {
            assertThat(MediaDeliveryController.isUpstreamStorageFailure(
                    new MediaDeliveryController.StorageStreamException("stream failure", new IOException("read timeout"))
            )).isTrue();

            assertThat(MediaDeliveryController.isUpstreamStorageFailure(
                    new RuntimeException("wrapped", new MediaDeliveryController.StorageStreamException("nested", null))
            )).isTrue();

            assertThat(MediaDeliveryController.isUpstreamStorageFailure(null)).isFalse();
            assertThat(MediaDeliveryController.isUpstreamStorageFailure(new IOException("broken pipe"))).isFalse();
        }

        @Test
        @DisplayName("5. Client disconnect identification: matches connection reset, broken pipe, client abort, socket write error")
        void shouldIdentifyClientDisconnects() {
            assertThat(MediaDeliveryController.isClientDisconnect(new IOException("Connection reset by peer"))).isTrue();
            assertThat(MediaDeliveryController.isClientDisconnect(new IOException("Broken pipe"))).isTrue();
            assertThat(MediaDeliveryController.isClientDisconnect(new IOException("An established connection was aborted by the software in your host machine"))).isTrue();
            assertThat(MediaDeliveryController.isClientDisconnect(new IOException("Socket write error"))).isTrue();
            assertThat(MediaDeliveryController.isClientDisconnect(new IOException("software caused connection abort"))).isTrue();

            assertThat(MediaDeliveryController.isClientDisconnect(null)).isFalse();
            assertThat(MediaDeliveryController.isClientDisconnect(new IOException("Disk full"))).isFalse();
            assertThat(MediaDeliveryController.isClientDisconnect(
                    new MediaDeliveryController.StorageStreamException("Storage stream read failure", null)
            )).isFalse();
        }

        @Test
        @DisplayName("6. Premature upstream EOF: throws StorageStreamException with expected vs transferred bytes and closes stream")
        void shouldDetectPrematureUpstreamEofAndCloseStream() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            // Upstream promises 100 bytes (metadata size), but only provides 15 bytes before returning EOF (-1)
            byte[] truncatedBytes = new byte[15];
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream truncatedStream = trackingStream(truncatedBytes, streamClosed);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(truncatedStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            StreamingResponseBody body = response.getBody();
            assertThat(body).isNotNull();

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> body.writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Premature upstream EOF")
                    .hasMessageContaining("15 of 100 bytes");

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("7. Premature upstream EOF on Range 206: throws StorageStreamException with range expected vs transferred bytes")
        void shouldDetectPrematureUpstreamEofOnRangeRequest() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            // Range bytes=0-49 promises 50 bytes, but upstream stream only provides 20 bytes
            byte[] truncatedSlice = new byte[20];
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream truncatedStream = trackingStream(truncatedSlice, streamClosed);
            when(getMediaAssetContentUseCase.openRange(meta, 0, 50)).thenReturn(truncatedStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            request.addHeader(HttpHeaders.RANGE, "bytes=0-49");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo("50");
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-49/100");

            StreamingResponseBody body = response.getBody();
            assertThat(body).isNotNull();

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> body.writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Premature upstream EOF")
                    .hasMessageContaining("20 of 50 bytes");

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("8. Upstream stream read failure: wraps in StorageStreamException and closes stream")
        void shouldWrapUpstreamStreamReadFailureAndCloseStream() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream failingStream = new InputStream() {
                private int count = 0;
                @Override
                public int read() throws IOException {
                    if (++count > 10) {
                        throw new IOException("B2 connection dropped during read");
                    }
                    return 0x55;
                }
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (++count > 2) {
                        throw new IOException("B2 connection dropped during read");
                    }
                    b[off] = 0x55;
                    return 1;
                }
                @Override
                public void close() {
                    streamClosed.set(true);
                }
            };
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(failingStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            StreamingResponseBody body = response.getBody();
            assertThat(body).isNotNull();

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> body.writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Storage stream read failure")
                    .hasCauseInstanceOf(IOException.class);

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("9. Downstream client disconnect: gracefully exits without rethrowing exception and closes upstream stream")
        void shouldHandleDownstreamClientDisconnectGracefully() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream stream = trackingStream(VIDEO_DATA, streamClosed);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(stream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            StreamingResponseBody body = response.getBody();
            assertThat(body).isNotNull();

            OutputStream abortingClientOut = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Connection reset by peer");
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("Connection reset by peer");
                }
            };

            // Must exit cleanly without throwing exception
            body.writeTo(abortingClientOut);

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("10. Downstream non-disconnect write failure: rethrows IOException and closes upstream stream")
        void shouldRethrowDownstreamNonDisconnectWriteFailure() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream stream = trackingStream(VIDEO_DATA, streamClosed);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(stream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            StreamingResponseBody body = response.getBody();
            assertThat(body).isNotNull();

            OutputStream diskFullOut = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("No space left on device");
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("No space left on device");
                }
            };

            assertThatThrownBy(() -> body.writeTo(diskFullOut))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("No space left on device");

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("11. Bounded synthetic slow-stream: delivers byte-exact content with 206 Partial Content headers")
        void shouldDeliverByteExactContentUnderChunkedSlowStream() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            // Simulate slow chunked stream: emits 10 bytes per read with tracking
            byte[] expectedSlice = new byte[30];
            System.arraycopy(VIDEO_DATA, 10, expectedSlice, 0, 30);
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream slowChunkedStream = new InputStream() {
                private int offset = 0;
                @Override
                public int read() {
                    if (offset >= expectedSlice.length) return -1;
                    return expectedSlice[offset++] & 0xFF;
                }
                @Override
                public int read(byte[] b, int off, int len) {
                    if (offset >= expectedSlice.length) return -1;
                    int chunkSize = Math.min(len, Math.min(10, expectedSlice.length - offset));
                    System.arraycopy(expectedSlice, offset, b, off, chunkSize);
                    offset += chunkSize;
                    return chunkSize;
                }
                @Override
                public void close() {
                    streamClosed.set(true);
                }
            };

            when(getMediaAssetContentUseCase.openRange(meta, 10, 30)).thenReturn(slowChunkedStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + VIDEO_ID + "/content");
            request.addHeader(HttpHeaders.RANGE, "bytes=10-39");
            AsyncWebRequest asyncWebRequest = org.mockito.Mockito.mock(AsyncWebRequest.class);
            WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
            asyncManager.setAsyncWebRequest(asyncWebRequest);

            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    VIDEO_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 10-39/100");
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo("30");
            assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            response.getBody().writeTo(clientOut);

            assertThat(clientOut.toByteArray()).isEqualTo(expectedSlice);
            assertThat(streamClosed.get()).isTrue();
            // Verify async timeout was scoped to 30 minutes (1,800,000 ms), protecting against 30s cutoff
            verify(asyncWebRequest).setTimeout(1_800_000L);
        }

        @Test
        @DisplayName("12. Repeated sequential Range seeks: delivers independent byte slices without interference")
        void shouldHandleMultipleSequentialRangeSeeks() throws Exception {
            GetMediaAssetContentMetadataResult meta = videoMetadata();
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(VIDEO_ID)))
                    .thenReturn(meta);

            // Seek 1: bytes 0-19
            byte[] slice1 = new byte[20];
            System.arraycopy(VIDEO_DATA, 0, slice1, 0, 20);
            AtomicBoolean closed1 = new AtomicBoolean(false);
            when(getMediaAssetContentUseCase.openRange(meta, 0, 20)).thenReturn(trackingStream(slice1, closed1));

            MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/media/assets/" + VIDEO_ID + "/content");
            req1.addHeader(HttpHeaders.RANGE, "bytes=0-19");
            ResponseEntity<StreamingResponseBody> resp1 = controller.deliverAssetContent(
                    VIDEO_ID, req1, new org.springframework.web.context.request.ServletWebRequest(req1));
            ByteArrayOutputStream out1 = new ByteArrayOutputStream();
            resp1.getBody().writeTo(out1);
            assertThat(out1.toByteArray()).isEqualTo(slice1);
            assertThat(closed1.get()).isTrue();

            // Seek 2: bytes 50-69
            byte[] slice2 = new byte[20];
            System.arraycopy(VIDEO_DATA, 50, slice2, 0, 20);
            AtomicBoolean closed2 = new AtomicBoolean(false);
            when(getMediaAssetContentUseCase.openRange(meta, 50, 20)).thenReturn(trackingStream(slice2, closed2));

            MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/media/assets/" + VIDEO_ID + "/content");
            req2.addHeader(HttpHeaders.RANGE, "bytes=50-69");
            ResponseEntity<StreamingResponseBody> resp2 = controller.deliverAssetContent(
                    VIDEO_ID, req2, new org.springframework.web.context.request.ServletWebRequest(req2));
            ByteArrayOutputStream out2 = new ByteArrayOutputStream();
            resp2.getBody().writeTo(out2);
            assertThat(out2.toByteArray()).isEqualTo(slice2);
            assertThat(closed2.get()).isTrue();
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Exact Streaming Byte Bounds Enforcement Tests (MS-08G3-C4)")
    class ExactStreamingByteBoundsEnforcementTests {

        private static final UUID BOUNDS_ASSET_ID =
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        private static final String BOUNDS_MIME = "video/mp4";
        private static final String BOUNDS_HASH =
                "f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3f3";
        private static final byte[] OVERLONG_150 = new byte[150];
        private static final byte[] OVERLONG_100 = new byte[100];
        private static final byte[] EXACT_100 = new byte[100];

        static {
            for (int i = 0; i < OVERLONG_150.length; i++) {
                OVERLONG_150[i] = (byte) (i & 0xFF);
            }
            System.arraycopy(OVERLONG_150, 0, OVERLONG_100, 0, 100);
            System.arraycopy(OVERLONG_150, 0, EXACT_100, 0, 100);
        }

        private GetMediaAssetContentMetadataResult boundsMetadata(long size) {
            return new GetMediaAssetContentMetadataResult(
                    BOUNDS_ASSET_ID,
                    1,
                    size,
                    BOUNDS_MIME,
                    BOUNDS_HASH,
                    null
            );
        }

        @Test
        @DisplayName("Test A: Overlong upstream stream on HTTP 206 delivers exact range slice and closes stream")
        void testA_OverlongUpstreamOnHttp206DeliversExactRangeSlice() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            // Range bytes=0-49 expects exactly 50 bytes, but upstream provides 100 bytes
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream overlongStream = trackingStream(OVERLONG_100, streamClosed);
            when(getMediaAssetContentUseCase.openRange(meta, 0, 50)).thenReturn(overlongStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            request.addHeader(HttpHeaders.RANGE, "bytes=0-49");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo("50");
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-49/100");

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            response.getBody().writeTo(clientOut);

            // Must receive exactly 50 bytes, not 100 bytes
            byte[] deliveredBytes = clientOut.toByteArray();
            assertThat(deliveredBytes).hasSize(50);
            byte[] expectedSlice = new byte[50];
            System.arraycopy(OVERLONG_100, 0, expectedSlice, 0, 50);
            assertThat(deliveredBytes).isEqualTo(expectedSlice);
            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Test B: Overlong upstream stream on HTTP 200 delivers exact full content and closes stream")
        void testB_OverlongUpstreamOnHttp200DeliversExactContentLength() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            // Metadata declares 100 bytes, but upstream provides 150 bytes
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream overlongStream = trackingStream(OVERLONG_150, streamClosed);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(overlongStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo("100");

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            response.getBody().writeTo(clientOut);

            // Must receive exactly 100 bytes, not 150 bytes
            byte[] deliveredBytes = clientOut.toByteArray();
            assertThat(deliveredBytes).hasSize(100);
            assertThat(deliveredBytes).isEqualTo(EXACT_100);
            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Test C: Exact-length stream completes normally without trailing read and closes stream")
        void testC_ExactLengthStreamCompletesNormallyAndClosesStream() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream exactStream = trackingStream(EXACT_100, streamClosed);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(exactStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            response.getBody().writeTo(clientOut);

            assertThat(clientOut.toByteArray()).isEqualTo(EXACT_100);
            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Test D: Premature EOF on HTTP 200 throws StorageStreamException and closes stream")
        void testD_PrematureEofOnHttp200ThrowsStorageStreamException() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            byte[] shortBytes = new byte[15];
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream shortStream = trackingStream(shortBytes, streamClosed);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(shortStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> response.getBody().writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Premature upstream EOF")
                    .hasMessageContaining("15 of 100 bytes");

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Test E: Premature EOF on Range 206 throws StorageStreamException and closes stream")
        void testE_PrematureEofOnRange206ThrowsStorageStreamException() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            byte[] shortSlice = new byte[20];
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream shortStream = trackingStream(shortSlice, streamClosed);
            when(getMediaAssetContentUseCase.openRange(meta, 0, 50)).thenReturn(shortStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            request.addHeader(HttpHeaders.RANGE, "bytes=0-49");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> response.getBody().writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Premature upstream EOF")
                    .hasMessageContaining("20 of 50 bytes");

            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Test F1: Null input stream on asset delivery when expectedLength > 0 throws StorageStreamException")
        void testF1_NullInputStreamOnAssetDeliveryThrowsStorageStreamException() {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);
            when(getMediaAssetContentUseCase.open(meta)).thenReturn(null);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> response.getBody().writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Storage stream is null");
        }

        @Test
        @DisplayName("Test F2: Null content stream on variant delivery when sizeBytes > 0 throws StorageStreamException")
        void testF2_NullContentOnVariantDeliveryThrowsStorageStreamException() {
            GetMediaAssetContentResult mockResult = org.mockito.Mockito.mock(GetMediaAssetContentResult.class);
            when(mockResult.isRedirect()).thenReturn(false);
            when(mockResult.content()).thenReturn(null);
            when(mockResult.sizeBytes()).thenReturn(50L);
            when(mockResult.mimeType()).thenReturn("image/jpeg");
            when(mockResult.contentHash()).thenReturn(BOUNDS_HASH);

            when(getMediaImageVariantContentUseCase.execute(new GetMediaImageVariantContentQuery(BOUNDS_ASSET_ID, "thumb")))
                    .thenReturn(mockResult);

            org.springframework.mock.web.MockHttpServletRequest servletRequest =
                    new org.springframework.mock.web.MockHttpServletRequest("GET",
                            "/media/assets/" + BOUNDS_ASSET_ID + "/variants/thumb");
            ResponseEntity<StreamingResponseBody> response = controller.deliverVariantContent(
                    BOUNDS_ASSET_ID,
                    "thumb",
                    new org.springframework.web.context.request.ServletWebRequest(servletRequest)
            );

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            assertThatThrownBy(() -> response.getBody().writeTo(clientOut))
                    .isInstanceOf(MediaDeliveryController.StorageStreamException.class)
                    .hasMessageContaining("Storage stream is null");
        }

        @Test
        @DisplayName("Test G: Read boundary capping via tracking InputStream caps len to remaining and halts on boundary")
        void testG_ReadBoundaryCappingRestrictsRequestedBytesAndHalts() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            // Range bytes=0-49 expects exactly 50 bytes
            long expectedRangeLength = 50L;
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicInteger readCallsAfterExpected = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicBoolean requestedLengthExceeded = new java.util.concurrent.atomic.AtomicBoolean(false);

            InputStream boundaryTrackingStream = new InputStream() {
                private long supplied = 0;

                @Override
                public int read() {
                    if (supplied >= expectedRangeLength) {
                        readCallsAfterExpected.incrementAndGet();
                    }
                    supplied++;
                    return 0x77;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (supplied >= expectedRangeLength) {
                        readCallsAfterExpected.incrementAndGet();
                        return -1;
                    }
                    long remaining = expectedRangeLength - supplied;
                    if (len > remaining) {
                        requestedLengthExceeded.set(true);
                    }
                    int bytesToSupply = (int) Math.min(len, remaining);
                    for (int i = 0; i < bytesToSupply; i++) {
                        b[off + i] = 0x77;
                    }
                    supplied += bytesToSupply;
                    return bytesToSupply;
                }

                @Override
                public void close() {
                    streamClosed.set(true);
                }
            };

            when(getMediaAssetContentUseCase.openRange(meta, 0, 50)).thenReturn(boundaryTrackingStream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            request.addHeader(HttpHeaders.RANGE, "bytes=0-49");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
            response.getBody().writeTo(clientOut);

            // Assert exact bytes delivered
            assertThat(clientOut.toByteArray()).hasSize(50);
            // Assert len requested was never greater than remaining
            assertThat(requestedLengthExceeded.get()).isFalse();
            // Assert inputStream.read was never invoked after reaching expected boundary
            assertThat(readCallsAfterExpected.get()).isEqualTo(0);
            // Assert stream was closed
            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("Test H: Client disconnect on range response exits cleanly without wrapping as upstream truncation")
        void testH_ClientDisconnectRegressionHandledGracefully() throws Exception {
            GetMediaAssetContentMetadataResult meta = boundsMetadata(100L);
            when(getMediaAssetContentUseCase.resolveMetadata(new GetMediaAssetContentQuery(BOUNDS_ASSET_ID)))
                    .thenReturn(meta);

            AtomicBoolean streamClosed = new AtomicBoolean(false);
            InputStream stream = trackingStream(OVERLONG_100, streamClosed);
            when(getMediaAssetContentUseCase.openRange(meta, 0, 50)).thenReturn(stream);

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/media/assets/" + BOUNDS_ASSET_ID + "/content");
            request.addHeader(HttpHeaders.RANGE, "bytes=0-49");
            ResponseEntity<StreamingResponseBody> response = controller.deliverAssetContent(
                    BOUNDS_ASSET_ID,
                    request,
                    new org.springframework.web.context.request.ServletWebRequest(request)
            );

            OutputStream abortingClientOut = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Connection reset by peer");
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("Connection reset by peer");
                }
            };

            // Must exit cleanly without throwing StorageStreamException
            response.getBody().writeTo(abortingClientOut);

            assertThat(streamClosed.get()).isTrue();
        }
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
