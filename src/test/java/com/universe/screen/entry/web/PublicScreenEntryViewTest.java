package com.universe.screen.entry.web;

import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.domain.ScreenEntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicScreenEntryView Unit Tests")
class PublicScreenEntryViewTest {

    @Test
    @DisplayName("Should resolve 300px variant and content URL for non-null thumbnail")
    void shouldResolveThumbnailUrls() {
        UUID thumbId = UUID.randomUUID();
        PublicScreenEntryDTO dto = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", "Tóm tắt", 1440L, thumbId, true
        );
        PublicScreenEntryView view = PublicScreenEntryView.fromDTO(dto);

        assertThat(view.displayThumbnailUrl()).isEqualTo("/media/assets/" + thumbId + "/variants/w300");
        assertThat(view.fallbackThumbnailUrl()).isEqualTo("/media/assets/" + thumbId + "/content");
    }

    @Test
    @DisplayName("Should return null thumbnail URLs when thumbnailMediaAssetId is null")
    void shouldReturnNullThumbnailUrlsWhenNull() {
        PublicScreenEntryDTO dto = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, null, null, false
        );
        PublicScreenEntryView view = PublicScreenEntryView.fromDTO(dto);

        assertThat(view.displayThumbnailUrl()).isNull();
        assertThat(view.fallbackThumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("Should format durations correctly")
    void shouldFormatDurations() {
        PublicScreenEntryDTO dto1 = new PublicScreenEntryDTO(UUID.randomUUID(), "ep-1", ScreenEntryType.EPISODE, 1, "T1", null, 1445L, null, false);
        assertThat(PublicScreenEntryView.fromDTO(dto1).formattedDuration()).isEqualTo("24:05");

        PublicScreenEntryDTO dto2 = new PublicScreenEntryDTO(UUID.randomUUID(), "ep-2", ScreenEntryType.FEATURE, null, "F1", null, 7320L, null, false);
        assertThat(PublicScreenEntryView.fromDTO(dto2).formattedDuration()).isEqualTo("2:02:00");

        PublicScreenEntryDTO dto3 = new PublicScreenEntryDTO(UUID.randomUUID(), "ep-3", ScreenEntryType.SPECIAL, 1, "S1", null, null, null, false);
        assertThat(PublicScreenEntryView.fromDTO(dto3).formattedDuration()).isNull();

        PublicScreenEntryDTO dto4 = new PublicScreenEntryDTO(UUID.randomUUID(), "ep-4", ScreenEntryType.SPECIAL, 1, "S1", null, 0L, null, false);
        assertThat(PublicScreenEntryView.fromDTO(dto4).formattedDuration()).isNull();
    }

    @Test
    @DisplayName("Should produce correct display headings and type badge labels for all types")
    void shouldProduceDisplayHeadingsAndTypeBadges() {
        PublicScreenEntryView ep = PublicScreenEntryView.fromDTO(new PublicScreenEntryDTO(UUID.randomUUID(), "e", ScreenEntryType.EPISODE, 5, "Xuất sơn", null, null, null, false));
        assertThat(ep.displayHeading()).isEqualTo("Tập 5");
        assertThat(ep.typeBadgeText()).isEqualTo("Tập phim");
        assertThat(ep.hasDistinctTitle()).isTrue();

        PublicScreenEntryView epNoCustomTitle = PublicScreenEntryView.fromDTO(new PublicScreenEntryDTO(UUID.randomUUID(), "e", ScreenEntryType.EPISODE, 5, "Tập 5", null, null, null, false));
        assertThat(epNoCustomTitle.hasDistinctTitle()).isFalse();

        PublicScreenEntryView feat = PublicScreenEntryView.fromDTO(new PublicScreenEntryDTO(UUID.randomUUID(), "f", ScreenEntryType.FEATURE, null, "Bản chiếu rạp", null, null, null, false));
        assertThat(feat.displayHeading()).isEqualTo("Bản chiếu chính");
        assertThat(feat.typeBadgeText()).isEqualTo("Phim lẻ");

        PublicScreenEntryView sp = PublicScreenEntryView.fromDTO(new PublicScreenEntryDTO(UUID.randomUUID(), "s", ScreenEntryType.SPECIAL, 2, "Ngoại truyện", null, null, null, false));
        assertThat(sp.displayHeading()).isEqualTo("Ngoại truyện 2");
        assertThat(sp.typeBadgeText()).isEqualTo("Ngoại truyện");

        PublicScreenEntryView tr = PublicScreenEntryView.fromDTO(new PublicScreenEntryDTO(UUID.randomUUID(), "t", ScreenEntryType.TRAILER, 1, "Trailer 1", null, null, null, false));
        assertThat(tr.displayHeading()).isEqualTo("Trailer 1");
        assertThat(tr.typeBadgeText()).isEqualTo("Trailer");
    }
}
