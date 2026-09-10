package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort;
import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort.MediaAssetCurrentMetadataSnapshot;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMediaAssetCurrentMetadataUseCaseTest {

    private static final UUID ASSET_ID = UUID.fromString("a2100000-0000-0000-0000-000000000001");

    @Mock
    private MediaAssetCurrentMetadataQueryPort queryPort;

    @ParameterizedTest
    @MethodSource("metadataStates")
    void returnsResolvedMetadataWithoutFilteringLifecycleOrVisibility(
            MediaAssetStatus status,
            MediaVisibility visibility
    ) {
        when(queryPort.findByAssetId(ASSET_ID)).thenReturn(Optional.of(snapshot(
                status, visibility, ASSET_ID, 2
        )));

        MediaAssetCurrentMetadataResult result = useCase().execute(
                new GetMediaAssetCurrentMetadataQuery(ASSET_ID)
        );

        assertThat(result).isEqualTo(new MediaAssetCurrentMetadataResult(
                ASSET_ID, status, visibility, 2
        ));
    }

    @Test
    void distinguishesAMissingAsset() {
        when(queryPort.findByAssetId(ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase().execute(new GetMediaAssetCurrentMetadataQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    void rejectsAnAssetWhoseDeclaredCurrentVersionRowIsMissing() {
        when(queryPort.findByAssetId(ASSET_ID)).thenReturn(Optional.of(snapshot(
                MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, null, null
        )));

        assertThatThrownBy(() -> useCase().execute(new GetMediaAssetCurrentMetadataQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class)
                .hasMessageContaining("2");
    }

    @Test
    void rejectsAProjectionThatDoesNotResolveTheDeclaredVersionExactly() {
        when(queryPort.findByAssetId(ASSET_ID)).thenReturn(Optional.of(snapshot(
                MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, ASSET_ID, 1
        )));

        assertThatThrownBy(() -> useCase().execute(new GetMediaAssetCurrentMetadataQuery(ASSET_ID)))
                .isInstanceOf(MediaAssetVersionNotFoundException.class);
    }

    private GetMediaAssetCurrentMetadataUseCase useCase() {
        return new GetMediaAssetCurrentMetadataUseCase(queryPort);
    }

    private static MediaAssetCurrentMetadataSnapshot snapshot(
            MediaAssetStatus status,
            MediaVisibility visibility,
            UUID versionAssetId,
            Integer versionNumber
    ) {
        return new MediaAssetCurrentMetadataSnapshot(
                ASSET_ID, status, visibility, 2, versionAssetId, versionNumber
        );
    }

    private static Stream<Arguments> metadataStates() {
        return Stream.of(
                Arguments.of(MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC),
                Arguments.of(MediaAssetStatus.ARCHIVED, MediaVisibility.PRIVATE),
                Arguments.of(MediaAssetStatus.DELETED, MediaVisibility.RESTRICTED)
        );
    }
}
