package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.ClientTagConflictException;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class AssignMediaAssetClientTagUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final ClockPort FIXED_CLOCK = () -> FIXED_NOW;
    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    private AssignMediaAssetClientTagUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new AssignMediaAssetClientTagUseCase(mediaAssetRepositoryPort, FIXED_CLOCK);
    }

    private MediaAsset createAssetWithTag(String tag) {
        return MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0,
                tag
        );
    }

    @Test
    @DisplayName("assigns client tag when currently null and persists")
    void shouldAssignClientTagWhenNull() {
        MediaAsset asset = createAssetWithTag(null);
        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));

        useCase.execute(new AssignMediaAssetClientTagCommand(ASSET_ID, "wiki.article.cover"));

        ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
        verify(mediaAssetRepositoryPort).save(captor.capture());
        MediaAsset saved = captor.getValue();
        assertThat(saved.getClientTag()).isEqualTo("wiki.article.cover");
        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("idempotent noop when assigning identical client tag")
    void shouldBeIdempotentNoopWhenAssigningSameTag() {
        MediaAsset asset = createAssetWithTag("wiki.article.cover");
        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));

        useCase.execute(new AssignMediaAssetClientTagCommand(ASSET_ID, "wiki.article.cover"));

        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("idempotent noop when assigning identical client tag even if clock is older than updatedAt")
    void shouldBeIdempotentNoopWhenAssigningSameTagEvenIfClockIsOlder() {
        MediaAsset asset = MediaAsset.rehydrate(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                com.universe.media.domain.MediaAssetStatus.ACTIVE,
                1,
                T0,
                FIXED_NOW,
                "wiki.article.cover"
        );
        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));

        // Use case with older clock (T0 is before FIXED_NOW)
        AssignMediaAssetClientTagUseCase olderClockUseCase =
                new AssignMediaAssetClientTagUseCase(mediaAssetRepositoryPort, () -> T0);

        olderClockUseCase.execute(new AssignMediaAssetClientTagCommand(ASSET_ID, "wiki.article.cover"));

        verify(mediaAssetRepositoryPort, never()).save(any());
        assertThat(asset.getClientTag()).isEqualTo("wiki.article.cover");
        assertThat(asset.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("throws ClientTagConflictException when asset already has different client tag")
    void shouldThrowConflictWhenAssetHasDifferentTag() {
        MediaAsset asset = createAssetWithTag("wiki.article.cover");
        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> useCase.execute(new AssignMediaAssetClientTagCommand(ASSET_ID, "novel.chapter.illustration")))
                .isInstanceOf(ClientTagConflictException.class)
                .hasMessageContaining("wiki.article.cover")
                .hasMessageContaining("novel.chapter.illustration");

        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("throws MediaAssetNotFoundException when asset does not exist")
    void shouldThrowNotFoundWhenAssetDoesNotExist() {
        when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(new AssignMediaAssetClientTagCommand(ASSET_ID, "wiki.article.cover")))
                .isInstanceOf(MediaAssetNotFoundException.class)
                .hasMessageContaining(ASSET_ID.toString());

        verify(mediaAssetRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("fails fast on null arguments")
    void shouldFailFastOnNullArguments() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> useCase.execute(new AssignMediaAssetClientTagCommand(null, "tag")))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> useCase.execute(new AssignMediaAssetClientTagCommand(ASSET_ID, null)))
                .isInstanceOf(NullPointerException.class);
    }
}
