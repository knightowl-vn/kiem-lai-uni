package com.universe.media.entry.delivery;

import com.universe.media.application.asset.GetMediaAssetContentQuery;
import com.universe.media.application.asset.GetMediaAssetContentResult;
import com.universe.media.application.asset.GetMediaAssetContentUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.domain.StorageKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private GetMediaAssetContentUseCase getMediaAssetContentUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MediaDeliveryController controller = new MediaDeliveryController(getMediaAssetContentUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /media/assets/{assetId}/content returns 200 with streamed bytes and headers")
    void shouldDeliverMediaAssetContentSuccessfully() throws Exception {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream trackingStream = new ByteArrayInputStream(payload) {
            @Override
            public void close() throws IOException {
                super.close();
                closed.set(true);
            }
        };

        GetMediaAssetContentResult result = new GetMediaAssetContentResult(
                trackingStream,
                payload.length,
                "image/webp",
                HASH
        );

        when(getMediaAssetContentUseCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .thenReturn(result);

        MvcResult mvcResult = mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + HASH + "\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/webp"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(payload.length)))
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().bytes(payload));

        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("GET /media/assets/{assetId}/content with matching If-None-Match returns 304 and closes stream")
    void shouldReturn304AndCloseStreamWhenIfNoneMatchMatches() throws Exception {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream trackingStream = new ByteArrayInputStream(payload) {
            @Override
            public void close() throws IOException {
                super.close();
                closed.set(true);
            }
        };

        GetMediaAssetContentResult result = new GetMediaAssetContentResult(
                trackingStream,
                payload.length,
                "image/webp",
                HASH
        );

        when(getMediaAssetContentUseCase.execute(new GetMediaAssetContentQuery(ASSET_ID)))
                .thenReturn(result);

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"" + HASH + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + HASH + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"));

        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("GET /media/assets/{assetId}/content when asset not found returns 404")
    void shouldReturn404WhenAssetNotFound() throws Exception {
        when(getMediaAssetContentUseCase.execute(any()))
                .thenThrow(new MediaAssetNotFoundException(ASSET_ID));

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /media/assets/{assetId}/content when version not found returns 404")
    void shouldReturn404WhenVersionNotFound() throws Exception {
        when(getMediaAssetContentUseCase.execute(any()))
                .thenThrow(new MediaAssetVersionNotFoundException(ASSET_ID, 1));

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /media/assets/{assetId}/content when binary object not found returns 404")
    void shouldReturn404WhenStorageObjectNotFound() throws Exception {
        when(getMediaAssetContentUseCase.execute(any()))
                .thenThrow(new StorageObjectNotFoundException(StorageKey.of("objects/missing")));

        mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /media/assets/{assetId}/content when provider mismatch throws StorageException")
    void shouldPropagateStorageExceptionWhenProviderMismatch() {
        when(getMediaAssetContentUseCase.execute(any()))
                .thenThrow(new StorageException("Storage provider mismatch"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> mockMvc.perform(get("/media/assets/{assetId}/content", ASSET_ID))
        )).hasCauseInstanceOf(StorageException.class);
    }
}
