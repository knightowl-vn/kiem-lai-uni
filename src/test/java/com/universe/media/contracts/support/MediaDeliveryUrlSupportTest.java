package com.universe.media.contracts.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MediaDeliveryUrlSupportTest {

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("contentUrl returns canonical content endpoint for non-null assetId and null for null assetId")
    void shouldResolveContentUrl() {
        assertThat(MediaDeliveryUrlSupport.contentUrl(ASSET_ID))
                .isEqualTo("/media/assets/11111111-1111-1111-1111-111111111111/content");
        assertThat(MediaDeliveryUrlSupport.contentUrl(null))
                .isNull();
    }

    @Test
    @DisplayName("variantUrl with targetWidth returns canonical variant endpoint for non-null assetId and null for null assetId")
    void shouldResolveVariantUrlWithWidth() {
        assertThat(MediaDeliveryUrlSupport.variantUrl(ASSET_ID, 300))
                .isEqualTo("/media/assets/11111111-1111-1111-1111-111111111111/variants/w300");
        assertThat(MediaDeliveryUrlSupport.variantUrl(null, 300))
                .isNull();
    }

    @Test
    @DisplayName("parseContentAssetId parses exact canonical content URL to UUID")
    void shouldParseCanonicalContentUrl() {
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/11111111-1111-1111-1111-111111111111/content"))
                .contains(ASSET_ID);
    }

    @Test
    @DisplayName("parseContentAssetId returns empty for null, blank, whitespace-mutated, or noncanonical URLs")
    void shouldReturnEmptyForNonCanonicalUrls() {
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId(null))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId(""))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("   "))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId(" /media/assets/11111111-1111-1111-1111-111111111111/content"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/11111111-1111-1111-1111-111111111111/content "))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/11111111-1111-1111-1111-111111111111/variants/w300"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/11111111-1111-1111-1111-111111111111/variants/w1400"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("https://res.cloudinary.com/demo/image/upload/v1/sample.jpg"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("https://example.com/media/assets/11111111-1111-1111-1111-111111111111/content"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/11111111-1111-1111-1111-111111111111/content/extra"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets//content"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/not-a-valid-uuid-format-here/content"))
                .isEmpty();
        assertThat(MediaDeliveryUrlSupport.parseContentAssetId("/media/assets/11111111-1111-1111-1111-11111111111A/content"))
                .isEmpty();
    }
}

