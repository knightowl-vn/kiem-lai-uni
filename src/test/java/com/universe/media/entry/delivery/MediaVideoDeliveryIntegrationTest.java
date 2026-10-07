package com.universe.media.entry.delivery;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort;
import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort.MediaAssetContentDeliverySnapshot;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.variant.GetMediaImageVariantContentUseCase;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.storage.DefaultStorageProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring WebMvc integration test for VIDEO HTTP byte-range delivery (MS-08C3 #75).
 * <p>
 * Proves the complete delivery path:
 * HTTP Request -> MediaDeliveryController -> GetMediaAssetContentUseCase -> DefaultStorageProviderRegistry
 * -> BinaryStoragePort ("b2") -> StreamingResponseBody -> HTTP 200/206/416 response.
 * <p>
 * Classification: Category 2 (Real Spring / Media integration using test storage implementation).
 */
@WebMvcTest(controllers = MediaDeliveryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        SecurityBeanConfig.class,
        GetMediaAssetContentUseCase.class,
        DefaultStorageProviderRegistry.class,
        MediaVideoDeliveryIntegrationTest.DeliveryTestConfig.class
})
@TestPropertySource(properties = {
        "security.remember-me.key=test-remember-me-key-video-delivery-integration-test-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("MediaVideoDeliveryIntegrationTest - Real Spring WebMvc Delivery Pipeline")
class MediaVideoDeliveryIntegrationTest {

    private static final UUID VIDEO_ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VERSION_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final StorageKey VIDEO_STORAGE_KEY =
            StorageKey.of("objects/teaser-trailer.mp4");
    private static final String VIDEO_MIME = "video/mp4";
    private static final String VIDEO_HASH =
            "4444444444444444444444444444444444444444444444444444444444444444";
    private static final String ETAG = "\"" + VIDEO_HASH + "\"";

    private static final byte[] VIDEO_CONTENT = new byte[200];

    static {
        for (int i = 0; i < VIDEO_CONTENT.length; i++) {
            VIDEO_CONTENT[i] = (byte) (i & 0xFF);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryDeliveryQueryPort queryPort;

    @Autowired
    private TestB2StoragePort b2StoragePort;

    @MockBean
    private GetMediaImageVariantContentUseCase getMediaImageVariantContentUseCase;

    // Security mock beans required by SecurityBeanConfig
    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private com.universe.identity.application.ports.CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private com.universe.shared.security.AuthenticatedEmailResolver authenticatedEmailResolver;

    @BeforeEach
    void setUp() {
        b2StoragePort.reset();
        b2StoragePort.putObject(VIDEO_STORAGE_KEY, VIDEO_CONTENT);

        queryPort.clear();
        queryPort.save(new MediaAssetContentDeliverySnapshot(
                VIDEO_ASSET_ID,
                MediaAssetStatus.ACTIVE,
                MediaVisibility.PUBLIC,
                1,
                VERSION_ID,
                VIDEO_ASSET_ID,
                1,
                "b2",
                VIDEO_STORAGE_KEY.value(),
                null, // B2 video has no public CDN URL; it is delivered via Media delivery stream
                VIDEO_HASH,
                VIDEO_MIME,
                (long) VIDEO_CONTENT.length
        ));
    }

    @Nested
    @DisplayName("A. Full Content Delivery")
    class FullContentDeliveryTests {

        @Test
        @DisplayName("full GET returns 200 OK with video/mp4, Content-Length, streaming bytes, and closes stream")
        void shouldDeliverFullVideoContent() throws Exception {
            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "200"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(VIDEO_CONTENT));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }

        @Test
        @DisplayName("HEAD returns 200 OK with representation headers and zero body bytes without opening storage")
        void shouldHandleHeadRequestWithoutOpeningStorage() throws Exception {
            mockMvc.perform(head("/media/assets/{assetId}/content", VIDEO_ASSET_ID))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "200"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                    .andExpect(content().bytes(new byte[0]));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("B. Valid Byte Range Delivery")
    class ValidRangeDeliveryTests {

        @Test
        @DisplayName("valid Range: bytes=0-49 returns 206 Partial Content with exact bytes and Content-Range")
        void shouldDeliverInitialRange() throws Exception {
            byte[] expected = Arrays.copyOfRange(VIDEO_CONTENT, 0, 50);

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-49"))
                    .andExpect(status().isPartialContent())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-49/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "50"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isPartialContent())
                    .andExpect(content().bytes(expected));

            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("C. Seek-Style Range Delivery")
    class SeekStyleRangeTests {

        @Test
        @DisplayName("seek-style range with non-zero start offset returns 206 without full-object read")
        void shouldDeliverSeekRange() throws Exception {
            int start = 50;
            int end = 149;
            byte[] expected = Arrays.copyOfRange(VIDEO_CONTENT, start, end + 1);

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=" + start + "-" + end))
                    .andExpect(status().isPartialContent())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 50-149/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "100"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isPartialContent())
                    .andExpect(content().bytes(expected));

            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("D. Repeated Range Delivery")
    class RepeatedRangeTests {

        @Test
        @DisplayName("repeated independent range requests to same video return isolated responses and close all streams")
        void shouldHandleRepeatedRangeRequestsIndependently() throws Exception {
            // Seek 1: bytes=10-29 (20 bytes)
            byte[] slice1 = Arrays.copyOfRange(VIDEO_CONTENT, 10, 30);
            MvcResult res1 = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=10-29"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 10-29/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "20"))
                    .andReturn();
            mockMvc.perform(asyncDispatch(res1))
                    .andExpect(content().bytes(slice1));

            // Seek 2: bytes=100-149 (50 bytes)
            byte[] slice2 = Arrays.copyOfRange(VIDEO_CONTENT, 100, 150);
            MvcResult res2 = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=100-149"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 100-149/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "50"))
                    .andReturn();
            mockMvc.perform(asyncDispatch(res2))
                    .andExpect(content().bytes(slice2));

            // Seek 3: bytes=180-199 (20 bytes)
            byte[] slice3 = Arrays.copyOfRange(VIDEO_CONTENT, 180, 200);
            MvcResult res3 = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=180-199"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 180-199/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "20"))
                    .andReturn();
            mockMvc.perform(asyncDispatch(res3))
                    .andExpect(content().bytes(slice3));

            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(3);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("E. Open-Ended and Suffix Range Delivery")
    class OpenEndedAndSuffixRangeTests {

        @Test
        @DisplayName("open-ended range: bytes=150- returns from 150 through EOF with 206 Partial Content")
        void shouldDeliverOpenEndedRange() throws Exception {
            byte[] expected = Arrays.copyOfRange(VIDEO_CONTENT, 150, 200);

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=150-"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 150-199/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "50"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(expected));

            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }

        @Test
        @DisplayName("suffix range: bytes=-40 returns last 40 bytes with 206 Partial Content")
        void shouldDeliverSuffixRange() throws Exception {
            byte[] expected = Arrays.copyOfRange(VIDEO_CONTENT, 160, 200);

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=-40"))
                    .andExpect(status().isPartialContent())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 160-199/200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "40"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(expected));

            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("F. Malformed Range Delivery")
    class MalformedRangeTests {

        @Test
        @DisplayName("multi-range: bytes=0-10,20-30 is ignored and delivers full 200 response")
        void shouldIgnoreMultiRangeAndDeliverFullContent() throws Exception {
            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-10,20-30"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "200"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(VIDEO_CONTENT));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("unsupported range unit: items=0-10 is ignored and delivers full 200 response")
        void shouldIgnoreUnsupportedUnitAndDeliverFullContent() throws Exception {
            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "items=0-10"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "200"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(VIDEO_CONTENT));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("G. Unsatisfiable Range Delivery")
    class UnsatisfiableRangeTests {

        @ParameterizedTest
        @ValueSource(strings = {"bytes=200-", "bytes=300-400", "bytes=50-20", "bytes=abc", "bytes=-0"})
        @DisplayName("unsatisfiable or invalid ranges return 416 with Content-Range bytes */200 and never open storage")
        void shouldRejectUnsatisfiableRangeWith416(String rangeHeader) throws Exception {
            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, rangeHeader))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */200"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("H & I & J & K. Failure Semantics")
    class FailureSemanticsTests {

        @Test
        @DisplayName("H. Missing asset returns 404 Not Found")
        void shouldReturn404WhenAssetNotFound() throws Exception {
            UUID unknownId = UUID.randomUUID();
            mockMvc.perform(get("/media/assets/{assetId}/content", unknownId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("I. Missing provider object on range read returns 404 Not Found")
        void shouldReturn404WhenStorageObjectNotFound() throws Exception {
            b2StoragePort.delete(VIDEO_STORAGE_KEY);

            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-49"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("J. Provider range failure propagates StorageException cleanly")
        void shouldPropagateStorageExceptionWhenProviderFails() {
            b2StoragePort.simulateFailure = true;

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-49"))
            )).hasCauseInstanceOf(StorageException.class);
        }

        @Test
        @DisplayName("K. Partial stream read error still closes stream via try-with-resources")
        void shouldCloseStreamWhenStreamingFailsMidStream() throws Exception {
            b2StoragePort.simulateStreamReadError = true;

            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-49"))
                    .andExpect(status().isPartialContent())
                    .andReturn();

            org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> mockMvc.perform(asyncDispatch(result))
            );

            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("ETag and If-Range Semantics")
    class CachingAndConditionalTests {

        @Test
        @DisplayName("matching strong If-Range serves byte range with 206")
        void shouldServeRangeWhenIfRangeMatchesStrongETag() throws Exception {
            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9")
                            .header(HttpHeaders.IF_RANGE, ETAG))
                    .andExpect(status().isPartialContent())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isPartialContent())
                    .andExpect(content().bytes(Arrays.copyOfRange(VIDEO_CONTENT, 0, 10)));

            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("mismatched If-Range ignores range and returns 200 full content")
        void shouldServeFullContentWhenIfRangeMismatches() throws Exception {
            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=0-9")
                            .header(HttpHeaders.IF_RANGE, "\"different-etag\""))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "200"))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(content().bytes(VIDEO_CONTENT));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("mismatched If-Range with otherwise-unsatisfiable Range ignores range and delivers full 200 content")
        void shouldServeFullContentWhenIfRangeMismatchesEvenIfRangeIsUnsatisfiable() throws Exception {
            MvcResult result = mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=300-400")
                            .header(HttpHeaders.IF_RANGE, "\"stale-etag\""))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "200"))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(VIDEO_CONTENT));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(1);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.allStreamsClosed()).isTrue();
        }

        @Test
        @DisplayName("matching strong If-Range with unsatisfiable Range returns 416 without opening storage")
        void shouldReturn416WhenIfRangeMatchesStrongETagAndRangeIsUnsatisfiable() throws Exception {
            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.RANGE, "bytes=300-400")
                            .header(HttpHeaders.IF_RANGE, ETAG))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */200"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("matching If-None-Match returns 304 Not Modified without opening storage")
        void shouldReturn304WhenIfNoneMatchMatches() throws Exception {
            mockMvc.perform(get("/media/assets/{assetId}/content", VIDEO_ASSET_ID)
                            .header(HttpHeaders.IF_NONE_MATCH, ETAG)
                            .header(HttpHeaders.RANGE, "bytes=0-9"))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                    .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));

            assertThat(b2StoragePort.openCallCount.get()).isEqualTo(0);
            assertThat(b2StoragePort.openRangeCallCount.get()).isEqualTo(0);
        }
    }

    @TestConfiguration
    static class DeliveryTestConfig {

        @Bean
        public InMemoryDeliveryQueryPort inMemoryDeliveryQueryPort() {
            return new InMemoryDeliveryQueryPort();
        }

        @Bean
        public TestB2StoragePort testB2StoragePort() {
            return new TestB2StoragePort();
        }
    }

    static class InMemoryDeliveryQueryPort implements MediaAssetContentDeliveryQueryPort {
        private final Map<UUID, MediaAssetContentDeliverySnapshot> store = new ConcurrentHashMap<>();

        public void save(MediaAssetContentDeliverySnapshot snapshot) {
            store.put(snapshot.assetId(), snapshot);
        }

        public void clear() {
            store.clear();
        }

        @Override
        public Optional<MediaAssetContentDeliverySnapshot> findByAssetId(UUID assetId) {
            return Optional.ofNullable(store.get(assetId));
        }
    }

    static class TestB2StoragePort implements BinaryStoragePort {
        private final Map<StorageKey, byte[]> storage = new ConcurrentHashMap<>();
        private final List<TrackingStream> activeStreams = new java.util.concurrent.CopyOnWriteArrayList<>();

        final AtomicInteger openCallCount = new AtomicInteger(0);
        final AtomicInteger openRangeCallCount = new AtomicInteger(0);
        volatile boolean simulateFailure = false;
        volatile boolean simulateStreamReadError = false;

        public void putObject(StorageKey key, byte[] content) {
            storage.put(key, content);
        }

        public void reset() {
            storage.clear();
            activeStreams.clear();
            openCallCount.set(0);
            openRangeCallCount.set(0);
            simulateFailure = false;
            simulateStreamReadError = false;
        }

        public boolean allStreamsClosed() {
            return !activeStreams.isEmpty() && activeStreams.stream().allMatch(s -> s.closed.get());
        }

        @Override
        public StorageProviderId providerId() {
            return StorageProviderId.of("b2");
        }

        @Override
        public StoredBinaryObject store(StorageKey key, InputStream content, long sizeBytes, MimeType mimeType) {
            try {
                byte[] bytes = content.readAllBytes();
                storage.put(key, bytes);
                return StoredBinaryObject.of(StorageLocation.of(providerId(), key));
            } catch (IOException e) {
                throw new StorageException("Failed to store test object", e);
            }
        }

        @Override
        public InputStream open(StorageKey key) {
            if (simulateFailure) {
                throw new StorageException("Simulated B2 provider read failure");
            }
            openCallCount.incrementAndGet();
            byte[] data = storage.get(key);
            if (data == null) {
                throw new StorageObjectNotFoundException(key);
            }
            TrackingStream stream = new TrackingStream(new ByteArrayInputStream(data), simulateStreamReadError);
            activeStreams.add(stream);
            return stream;
        }

        @Override
        public InputStream openRange(StorageKey key, long startInclusive, long length) {
            if (simulateFailure) {
                throw new StorageException("Simulated B2 provider range failure");
            }
            openRangeCallCount.incrementAndGet();
            byte[] data = storage.get(key);
            if (data == null) {
                throw new StorageObjectNotFoundException(key);
            }
            if (startInclusive < 0 || length <= 0 || startInclusive >= data.length) {
                throw new IllegalArgumentException("Invalid range: start=" + startInclusive + ", len=" + length);
            }
            int endInclusive = (int) Math.min(startInclusive + length - 1, data.length - 1);
            int sliceLen = endInclusive - (int) startInclusive + 1;
            byte[] slice = new byte[sliceLen];
            System.arraycopy(data, (int) startInclusive, slice, 0, sliceLen);

            TrackingStream stream = new TrackingStream(new ByteArrayInputStream(slice), simulateStreamReadError);
            activeStreams.add(stream);
            return stream;
        }

        @Override
        public void delete(StorageKey key) {
            storage.remove(key);
        }
    }

    static class TrackingStream extends FilterInputStream {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final boolean simulateReadError;
        int bytesRead = 0;

        TrackingStream(InputStream in, boolean simulateReadError) {
            super(in);
            this.simulateReadError = simulateReadError;
        }

        @Override
        public int read() throws IOException {
            if (simulateReadError) {
                throw new IOException("Simulated network drop during video playback");
            }
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (simulateReadError) {
                throw new IOException("Simulated network drop during video playback");
            }
            return super.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }
}
