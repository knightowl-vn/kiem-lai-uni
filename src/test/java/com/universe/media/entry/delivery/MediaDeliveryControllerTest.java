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
