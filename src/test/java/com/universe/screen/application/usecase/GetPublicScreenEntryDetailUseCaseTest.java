package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenEntryDetailDTO;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.ports.ScreenVideoDeliveryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicScreenEntryDetailUseCase Unit Tests")
class GetPublicScreenEntryDetailUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepositoryPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    @Mock
    private ScreenVideoDeliveryPort videoDeliveryPort;

    private GetPublicScreenEntryDetailUseCase useCase;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetPublicScreenEntryDetailUseCase(titleRepositoryPort, entryRepositoryPort, videoDeliveryPort);
    }

    @Test
    @DisplayName("Should retrieve published entry detail under published title successfully with playable video")
    void shouldRetrievePublishedEntryDetailSuccessfully() {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", "剑来",
                "Tóm tắt", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
        );

        ScreenEntry entry = new ScreenEntry(
                entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Xuất Thế", "Nội dung tập 1", videoId, thumbId, 1440L,
                ScreenPublicationStatus.PUBLISHED, now, now, now
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(entry));
        when(videoDeliveryPort.resolvePlaybackDelivery(videoId))
                .thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(videoId, "/media/assets/" + videoId + "/content", "video/mp4")));

        PublicScreenEntryDetailDTO result = useCase.execute("kiem-lai-1", "tap-1");

        assertThat(result).isNotNull();
        assertThat(result.titleId()).isEqualTo(titleId);
        assertThat(result.titleSlug()).isEqualTo("kiem-lai-1");
        assertThat(result.titleDisplayName()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(result.titleStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(result.entryId()).isEqualTo(entryId);
        assertThat(result.entrySlug()).isEqualTo("tap-1");
        assertThat(result.entryType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(result.entryNumber()).isEqualTo(1);
        assertThat(result.entryTitle()).isEqualTo("Tập 1: Xuất Thế");
        assertThat(result.entrySynopsis()).isEqualTo("Nội dung tập 1");
        assertThat(result.durationSeconds()).isEqualTo(1440L);
        assertThat(result.thumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(result.hasPlayableVideo()).isTrue();

        verify(titleRepositoryPort).findPublishedBySlug("kiem-lai-1");
        verify(entryRepositoryPort).findPublishedByTitleIdAndSlug(titleId, "tap-1");
        verify(videoDeliveryPort).resolvePlaybackDelivery(videoId);
    }

    @Test
    @DisplayName("Should set hasPlayableVideo to false when video asset is attached but not authoritatively playable")
    void shouldSetHasPlayableVideoFalseWhenVideoAssetNotPlayable() {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
        );

        ScreenEntry entry = new ScreenEntry(
                entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, videoId, null, 1440L,
                ScreenPublicationStatus.PUBLISHED, now, now, now
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(entry));
        when(videoDeliveryPort.resolvePlaybackDelivery(videoId)).thenReturn(Optional.empty());

        PublicScreenEntryDetailDTO result = useCase.execute("kiem-lai-1", "tap-1");

        assertThat(result.hasPlayableVideo()).isFalse();
        verify(videoDeliveryPort).resolvePlaybackDelivery(videoId);
    }

    @Test
    @DisplayName("Should normalize slugs to lower case before querying repositories")
    void shouldNormalizeSlugsBeforeQuerying() {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
        );
        ScreenEntry entry = new ScreenEntry(
                entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1", null, null, null, 1200L,
                ScreenPublicationStatus.PUBLISHED, now, now, now
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "tap-1")).thenReturn(Optional.of(entry));

        PublicScreenEntryDetailDTO result = useCase.execute("  KIEM-LAI-1  ", "  TAP-1  ");

        assertThat(result).isNotNull();
        verify(titleRepositoryPort).findPublishedBySlug("kiem-lai-1");
        verify(entryRepositoryPort).findPublishedByTitleIdAndSlug(titleId, "tap-1");
    }

    @Test
    @DisplayName("Should reject when title slug is null or blank")
    void shouldRejectWhenTitleSlugIsNullOrBlank() {
        assertThatThrownBy(() -> useCase.execute(null, "tap-1"))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);

        assertThatThrownBy(() -> useCase.execute("   ", "tap-1"))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);

        verify(titleRepositoryPort, never()).findPublishedBySlug(any());
    }

    @Test
    @DisplayName("Should reject when entry slug is null or blank")
    void shouldRejectWhenEntrySlugIsNullOrBlank() {
        assertThatThrownBy(() -> useCase.execute("kiem-lai-1", null))
                .isInstanceOf(PublishedScreenEntryNotFoundException.class);

        assertThatThrownBy(() -> useCase.execute("kiem-lai-1", "   "))
                .isInstanceOf(PublishedScreenEntryNotFoundException.class);

        verify(titleRepositoryPort, never()).findPublishedBySlug(any());
    }

    @Test
    @DisplayName("Should throw PublishedScreenTitleNotFoundException when title does not exist or is not published")
    void shouldThrowWhenTitleNotFound() {
        when(titleRepositoryPort.findPublishedBySlug("unknown-title")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("unknown-title", "tap-1"))
                .isInstanceOf(PublishedScreenTitleNotFoundException.class);

        verify(entryRepositoryPort, never()).findPublishedByTitleIdAndSlug(any(), any());
    }

    @Test
    @DisplayName("Should throw PublishedScreenEntryNotFoundException when entry does not exist or is not published")
    void shouldThrowWhenEntryNotFound() {
        UUID titleId = UUID.randomUUID();
        ScreenTitle title = new ScreenTitle(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
        );

        when(titleRepositoryPort.findPublishedBySlug("kiem-lai-1")).thenReturn(Optional.of(title));
        when(entryRepositoryPort.findPublishedByTitleIdAndSlug(titleId, "unknown-entry")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("kiem-lai-1", "unknown-entry"))
                .isInstanceOf(PublishedScreenEntryNotFoundException.class);
    }
}
