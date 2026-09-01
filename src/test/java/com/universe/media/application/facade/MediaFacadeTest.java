package com.universe.media.application.facade;

import com.universe.media.application.asset.ArchiveMediaAssetCommand;
import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.ChangeMediaVisibilityCommand;
import com.universe.media.application.asset.ChangeMediaVisibilityUseCase;
import com.universe.media.application.asset.DeleteMediaAssetCommand;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.GetMediaAssetDetailQuery;
import com.universe.media.application.asset.GetMediaAssetDetailUseCase;
import com.universe.media.application.asset.MediaAssetDetailResult;
import com.universe.media.application.asset.MediaVersionItemResult;
import com.universe.media.application.asset.RestoreMediaAssetCommand;
import com.universe.media.application.asset.RestoreMediaAssetUseCase;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaFacadeTest {

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID VERSION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Instant T1 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final Instant T2 =
            Instant.parse("2026-09-01T12:00:00Z");

    @Mock
    private GetMediaAssetDetailUseCase getMediaAssetDetailUseCase;

    @Mock
    private ChangeMediaVisibilityUseCase changeMediaVisibilityUseCase;

    @Mock
    private ArchiveMediaAssetUseCase archiveMediaAssetUseCase;

    @Mock
    private RestoreMediaAssetUseCase restoreMediaAssetUseCase;

    @Mock
    private DeleteMediaAssetUseCase deleteMediaAssetUseCase;

    private MediaFacade facade;

    @BeforeEach
    void setUp() {
        facade = new MediaFacade(
                getMediaAssetDetailUseCase,
                changeMediaVisibilityUseCase,
                archiveMediaAssetUseCase,
                restoreMediaAssetUseCase,
                deleteMediaAssetUseCase
        );
    }

    @Test
    @DisplayName("getAssetDetail returns mapped public DTO with current version")
    void shouldReturnMappedAssetDetailDTO() {
        MediaVersionItemResult versionItem = new MediaVersionItemResult(
                VERSION_ID,
                ASSET_ID,
                1,
                "cloudinary",
                "covers/novel.webp",
                "https://cdn.universe.com/covers/novel.webp",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "image/webp",
                2048L,
                "novel.webp",
                T1
        );

        MediaAssetDetailResult appResult = new MediaAssetDetailResult(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                MediaAssetStatus.ACTIVE,
                1,
                T1,
                T2,
                versionItem
        );

        when(getMediaAssetDetailUseCase.execute(new GetMediaAssetDetailQuery(ASSET_ID)))
                .thenReturn(appResult);

        Optional<MediaAssetDetailDTO> optDetail = facade.getAssetDetail(ASSET_ID);

        assertThat(optDetail).isPresent();
        MediaAssetDetailDTO detail = optDetail.get();
        assertThat(detail.id()).isEqualTo(ASSET_ID);
        assertThat(detail.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(detail.visibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(detail.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(detail.currentVersionNumber()).isEqualTo(1);
        assertThat(detail.createdAt()).isEqualTo(T1);
        assertThat(detail.updatedAt()).isEqualTo(T2);

        MediaVersionDTO versionDto = detail.currentVersion();
        assertThat(versionDto).isNotNull();
        assertThat(versionDto.id()).isEqualTo(VERSION_ID);
        assertThat(versionDto.assetId()).isEqualTo(ASSET_ID);
        assertThat(versionDto.versionNumber()).isEqualTo(1);
        assertThat(versionDto.publicUrl()).isEqualTo("https://cdn.universe.com/covers/novel.webp");
        assertThat(versionDto.mimeType()).isEqualTo("image/webp");
        assertThat(versionDto.sizeBytes()).isEqualTo(2048L);
        assertThat(versionDto.originalFilename()).isEqualTo("novel.webp");
        assertThat(versionDto.createdAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("getAssetDetail returns Optional.empty() when asset is not found")
    void shouldReturnEmptyWhenAssetNotFound() {
        when(getMediaAssetDetailUseCase.execute(any(GetMediaAssetDetailQuery.class)))
                .thenThrow(new MediaAssetNotFoundException(ASSET_ID));

        Optional<MediaAssetDetailDTO> optDetail = facade.getAssetDetail(ASSET_ID);

        assertThat(optDetail).isEmpty();
    }

    @Test
    @DisplayName("getAssetDetail fails fast on null assetId and does not invoke use case")
    void shouldFailFastOnNullAssetId() {
        assertThatThrownBy(() -> facade.getAssetDetail(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Asset ID cannot be null.");

        verifyNoInteractions(getMediaAssetDetailUseCase);
    }

    @Test
    @DisplayName("getAssetDetail propagates MediaAssetVersionNotFoundException as data integrity failure")
    void shouldPropagateDataIntegrityExceptionWhenCurrentVersionMissing() {
        when(getMediaAssetDetailUseCase.execute(any(GetMediaAssetDetailQuery.class)))
                .thenThrow(new MediaAssetVersionNotFoundException(ASSET_ID, 1));

        assertThatThrownBy(() -> facade.getAssetDetail(ASSET_ID))
                .isInstanceOf(MediaAssetVersionNotFoundException.class)
                .hasMessageContaining(ASSET_ID.toString());
    }

    @Test
    @DisplayName("changeVisibility delegates with mapped command")
    void shouldDelegateChangeVisibility() {
        ChangeMediaVisibilityRequestDTO request =
                new ChangeMediaVisibilityRequestDTO(ASSET_ID, MediaVisibility.RESTRICTED);

        facade.changeVisibility(request);

        ArgumentCaptor<ChangeMediaVisibilityCommand> captor =
                ArgumentCaptor.forClass(ChangeMediaVisibilityCommand.class);
        verify(changeMediaVisibilityUseCase).execute(captor.capture());

        ChangeMediaVisibilityCommand cmd = captor.getValue();
        assertThat(cmd.assetId()).isEqualTo(ASSET_ID);
        assertThat(cmd.newVisibility()).isEqualTo(MediaVisibility.RESTRICTED);
    }

    @Test
    @DisplayName("archive delegates to ArchiveMediaAssetUseCase")
    void shouldDelegateArchive() {
        facade.archive(ASSET_ID);

        ArgumentCaptor<ArchiveMediaAssetCommand> captor =
                ArgumentCaptor.forClass(ArchiveMediaAssetCommand.class);
        verify(archiveMediaAssetUseCase).execute(captor.capture());

        assertThat(captor.getValue().assetId()).isEqualTo(ASSET_ID);
    }

    @Test
    @DisplayName("restore delegates to RestoreMediaAssetUseCase")
    void shouldDelegateRestore() {
        facade.restore(ASSET_ID);

        ArgumentCaptor<RestoreMediaAssetCommand> captor =
                ArgumentCaptor.forClass(RestoreMediaAssetCommand.class);
        verify(restoreMediaAssetUseCase).execute(captor.capture());

        assertThat(captor.getValue().assetId()).isEqualTo(ASSET_ID);
    }

    @Test
    @DisplayName("delete delegates to DeleteMediaAssetUseCase")
    void shouldDelegateDelete() {
        facade.delete(ASSET_ID);

        ArgumentCaptor<DeleteMediaAssetCommand> captor =
                ArgumentCaptor.forClass(DeleteMediaAssetCommand.class);
        verify(deleteMediaAssetUseCase).execute(captor.capture());

        assertThat(captor.getValue().assetId()).isEqualTo(ASSET_ID);
    }
}
