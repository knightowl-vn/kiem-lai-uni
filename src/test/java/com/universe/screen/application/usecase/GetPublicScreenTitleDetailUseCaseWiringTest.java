package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.ports.ScreenVideoDeliveryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.screen.entry.web.PublicScreenTitleDetailView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GetPublicScreenTitleDetailUseCase Spring Context & Wiring Test")
class GetPublicScreenTitleDetailUseCaseWiringTest {

    private final ScreenTitleRepositoryPort titleRepositoryPort = mock(ScreenTitleRepositoryPort.class);
    private final ScreenEntryRepositoryPort entryRepositoryPort = mock(ScreenEntryRepositoryPort.class);
    private final ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepositoryPort = mock(ScreenFranchiseMembershipRepositoryPort.class);
    private final ScreenFranchiseRepositoryPort franchiseRepositoryPort = mock(ScreenFranchiseRepositoryPort.class);
    private final ScreenVideoDeliveryPort videoDeliveryPort = mock(ScreenVideoDeliveryPort.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GetPublicScreenTitleDetailUseCase.class)
            .withBean(ScreenTitleRepositoryPort.class, () -> titleRepositoryPort)
            .withBean(ScreenEntryRepositoryPort.class, () -> entryRepositoryPort)
            .withBean(ScreenFranchiseMembershipRepositoryPort.class, () -> franchiseMembershipRepositoryPort)
            .withBean(ScreenFranchiseRepositoryPort.class, () -> franchiseRepositoryPort)
            .withBean(ScreenVideoDeliveryPort.class, () -> videoDeliveryPort);

    @Test
    @DisplayName("Spring DI resolves @Autowired 5-argument constructor and injects real ScreenVideoDeliveryPort bean")
    void shouldInstantiateBeanWithInjectedVideoDeliveryPort() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GetPublicScreenTitleDetailUseCase.class);

            GetPublicScreenTitleDetailUseCase useCase = context.getBean(GetPublicScreenTitleDetailUseCase.class);
            assertThat(useCase).isNotNull();

            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();
            UUID videoAssetId = UUID.randomUUID();
            Instant now = Instant.now();

            ScreenTitle title = new ScreenTitle(
                    titleId, "linh-kiem-su-ton", "Linh Kiếm Sư Tôn", null,
                    "Mô tả phim", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                    null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
            );

            ScreenEntry ep1 = new ScreenEntry(
                    entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1,
                    "Tập 1", null, videoAssetId, null, 1200L,
                    ScreenPublicationStatus.PUBLISHED, now, now, now
            );

            when(titleRepositoryPort.findPublishedBySlug("linh-kiem-su-ton")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(1);
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1));
            when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
            when(videoDeliveryPort.resolvePlaybackDelivery(videoAssetId))
                    .thenReturn(Optional.of(new ScreenPlaybackDeliveryInfo(
                            videoAssetId,
                            "/media/assets/" + videoAssetId + "/content",
                            "video/mp4"
                    )));

            PublicScreenTitleDetailDTO result = useCase.execute("linh-kiem-su-ton");

            assertThat(result).isNotNull();
            assertThat(result.entries()).hasSize(1);
            assertThat(result.entries().get(0).hasPlayableVideo()).isTrue();

            PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(result);
            assertThat(view.hasPlayableWatchEntry()).isTrue();
            assertThat(view.watchActionText()).isEqualTo("Xem ngay");
            verify(videoDeliveryPort).resolvePlaybackDelivery(videoAssetId);
        });
    }

    @Test
    @DisplayName("Spring context verifies playback readiness evaluates to false when video port returns empty")
    void shouldEvaluatePlaybackReadinessUsingInjectedPortWhenDeliveryEmpty() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GetPublicScreenTitleDetailUseCase useCase = context.getBean(GetPublicScreenTitleDetailUseCase.class);

            UUID titleId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();
            UUID videoAssetId = UUID.randomUUID();
            Instant now = Instant.now();

            ScreenTitle title = new ScreenTitle(
                    titleId, "linh-kiem-su-ton-2", "Linh Kiếm Sư Tôn 2", null,
                    "Mô tả phim", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                    null, null, ScreenPublicationStatus.PUBLISHED, now, now, now, 24
            );

            ScreenEntry ep1 = new ScreenEntry(
                    entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1,
                    "Tập 1", null, videoAssetId, null, 1200L,
                    ScreenPublicationStatus.PUBLISHED, now, now, now
            );

            when(titleRepositoryPort.findPublishedBySlug("linh-kiem-su-ton-2")).thenReturn(Optional.of(title));
            when(entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(titleId)).thenReturn(1);
            when(entryRepositoryPort.findPublishedByTitleId(titleId)).thenReturn(List.of(ep1));
            when(franchiseMembershipRepositoryPort.findByTitleId(titleId)).thenReturn(List.of());
            when(videoDeliveryPort.resolvePlaybackDelivery(videoAssetId)).thenReturn(Optional.empty());

            PublicScreenTitleDetailDTO result = useCase.execute("linh-kiem-su-ton-2");

            assertThat(result).isNotNull();
            assertThat(result.entries()).hasSize(1);
            assertThat(result.entries().get(0).hasPlayableVideo()).isFalse();

            PublicScreenTitleDetailView view = PublicScreenTitleDetailView.fromDTO(result);
            assertThat(view.hasPlayableWatchEntry()).isFalse();
            assertThat(view.watchActionText()).isEqualTo("Xem ngay");
            verify(videoDeliveryPort).resolvePlaybackDelivery(videoAssetId);
        });
    }

    @Test
    @DisplayName("Spring DI fails bean creation when ScreenVideoDeliveryPort is missing from context")
    void shouldFailContextInitializationWhenScreenVideoDeliveryPortMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(GetPublicScreenTitleDetailUseCase.class)
                .withBean(ScreenTitleRepositoryPort.class, () -> titleRepositoryPort)
                .withBean(ScreenEntryRepositoryPort.class, () -> entryRepositoryPort)
                .withBean(ScreenFranchiseMembershipRepositoryPort.class, () -> franchiseMembershipRepositoryPort)
                .withBean(ScreenFranchiseRepositoryPort.class, () -> franchiseRepositoryPort)
                // Intentionally missing ScreenVideoDeliveryPort
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(UnsatisfiedDependencyException.class)
                            .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
                });
    }
}
