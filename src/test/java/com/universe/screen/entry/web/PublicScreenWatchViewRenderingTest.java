package com.universe.screen.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenWatchDTO;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import com.universe.screen.application.usecase.GetPublicScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenWatchUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.shared.security.AuthenticatedEmailResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicScreenController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-screen-watch-rendering-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicScreenWatchViewRendering SSR Tests (Thymeleaf)")
class PublicScreenWatchViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GetPublicScreenCatalogUseCase getPublicScreenCatalogUseCase;

    @MockBean
    private GetPublicScreenTitleDetailUseCase getPublicScreenTitleDetailUseCase;

    @MockBean
    private GetPublicScreenEntryDetailUseCase getPublicScreenEntryDetailUseCase;

    @MockBean
    private GetPublicScreenWatchUseCase getPublicScreenWatchUseCase;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockBean
    private AccountStatusFilter accountStatusFilter;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private CustomAuthenticationFailureHandler customAuthenticationFailureHandler;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Renders watch page with native HTML5 video player when content is playable")
    void shouldRenderWatchPageWithPlayableVideoPlayer() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID videoAssetId = UUID.randomUUID();
        UUID thumbnailId = UUID.randomUUID();

        PublicScreenEntryDTO prev = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1: Khởi đầu", null, 1440L, null, true
        );
        PublicScreenEntryDTO next = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-3", ScreenEntryType.EPISODE, 3, "Tập 3: Tiếp diễn", null, 1500L, null, true
        );
        PublicScreenEntryDTO curr = new PublicScreenEntryDTO(
                entryId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2: Phong Vân", "Tóm tắt tập 2", 1440L, thumbnailId, true
        );

        UUID posterId = UUID.randomUUID();
        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, null, entryId, "tap-2", ScreenEntryType.EPISODE, 2,
                "Tập 2: Phong Vân", "Tóm tắt tập 2", 1440L, thumbnailId,
                true, "/media/assets/" + videoAssetId + "/content", "video/mp4",
                prev, next, List.of(prev, curr, next),
                10L, 100L, true,
                1300L, 1400L, true
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-1", "tap-2")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-1/watch/tap-2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Tập 2 - Kiếm Lai Phần 1 - Kiếm Lai Universe</title>")))
                // Compact now-playing heading above the stage
                .andExpect(content().string(containsString("class=\"screen-watch-now-playing-title\"")))
                .andExpect(content().string(containsString("Kiếm Lai Phần 1 · Tập 2")))
                // Breadcrumb and back links
                .andExpect(content().string(containsString("Quay lại")))
                .andExpect(content().string(containsString("Quay lại tác phẩm: Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1\"")))
                .andExpect(content().string(not(containsString("href=\"/screen/kiem-lai-1/entries/tap-2\""))))
                // Redundant below-stage entry header was removed
                .andExpect(content().string(not(containsString("screen-watch-entry-header"))))
                // Native HTML5 video player rendered with poster and truthful MIME type
                .andExpect(content().string(containsString("<video controls")))
                .andExpect(content().string(containsString("poster=\"/media/assets/" + thumbnailId + "/variants/w600\"")))
                .andExpect(content().string(containsString("src=\"/media/assets/" + videoAssetId + "/content\"")))
                .andExpect(content().string(containsString("type=\"video/mp4\"")))
                .andExpect(content().string(not(containsString("Video chưa sẵn sàng"))))
                // MS-08G2: Compact runtime player error overlay & ScreenPlayer progressive enhancement
                .andExpect(content().string(containsString("class=\"screen-watch-player-error\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerError\"")))
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(containsString("aria-live=\"assertive\"")))
                .andExpect(content().string(containsString("hidden")))
                .andExpect(content().string(containsString("Không thể phát video")))
                .andExpect(content().string(containsString("id=\"screenPlayerErrorMessage\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerRetryBtn\"")))
                .andExpect(content().string(containsString("Thử lại")))
                .andExpect(content().string(containsString("src=\"/js/screen/screen-player.js\"")))
                .andExpect(content().string(containsString("window.ScreenPlayer.init(document)")))
                // MS-08G2-UX01: Modern player controls, resume dialog, and next episode action
                .andExpect(content().string(containsString("id=\"screenPlayerWrap\"")))
                .andExpect(content().string(containsString("data-entry-id=\"" + entryId + "\"")))
                .andExpect(content().string(containsString("data-entry-label=\"Tập 2\"")))
                .andExpect(content().string(containsString("data-video-src=\"/media/assets/" + videoAssetId + "/content\"")))
                // MS-08G3-UX02-C2.1: Outro boundary dataset and Skip Outro button
                .andExpect(content().string(containsString("data-has-outro=\"true\"")))
                .andExpect(content().string(containsString("data-outro-start-seconds=\"1300\"")))
                .andExpect(content().string(containsString("data-outro-end-seconds=\"1400\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerSkipOutroBtn\"")))
                .andExpect(content().string(containsString("class=\"screen-player-skip-outro-btn\"")))
                .andExpect(content().string(containsString("Bỏ qua Outro")))
                // MS-08G2-UX01-P1: Pre-playback poster stage image layer & integrated 10s seek icons
                .andExpect(content().string(containsString("id=\"screenPlayerPoster\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerPosterImg\"")))
                .andExpect(content().string(containsString("data-fallback-src=")))
                .andExpect(content().string(containsString("data-secondary-fallback-src=")))
                .andExpect(content().string(containsString("id=\"screenPlayerControls\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerPlayPauseBtn\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerRewindBtn\"")))
                .andExpect(content().string(containsString("Tua lùi 10 giây")))
                .andExpect(content().string(containsString("id=\"screenPlayerForwardBtn\"")))
                .andExpect(content().string(containsString("Tua tới 10 giây")))
                .andExpect(content().string(containsString("class=\"screen-player-seek-icon\"")))
                .andExpect(content().string(containsString(">10</text>")))
                .andExpect(content().string(not(containsString("<span class=\"screen-player-seek-badge\">-10s</span>"))))
                .andExpect(content().string(not(containsString("<span class=\"screen-player-seek-badge\">+10s</span>"))))
                .andExpect(content().string(containsString("id=\"screenPlayerSeekSlider\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerSpeedBtn\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerSpeedMenu\"")))
                .andExpect(content().string(containsString("data-speed=\"0.5\"")))
                .andExpect(content().string(containsString("data-speed=\"2\"")))
                // MS-08G2-UX01-M1: Responsive controls, standalone PiP, and extensible Settings menu
                .andExpect(content().string(containsString("id=\"screenPlayerSettingsWrap\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerSettingsBtn\"")))
                .andExpect(content().string(containsString("aria-label=\"Cài đặt\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerSettingsMenu\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerSettingsSpeedBadge\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerPipBtn\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerFullscreenBtn\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerResumeModal\"")))
                .andExpect(content().string(containsString("Tiếp tục xem Tập 2?")))
                .andExpect(content().string(containsString("id=\"screenPlayerResumeConfirmBtn\"")))
                .andExpect(content().string(containsString("Xem tiếp")))
                .andExpect(content().string(containsString("id=\"screenPlayerResumeResetBtn\"")))
                .andExpect(content().string(containsString("Xem từ đầu")))
                .andExpect(content().string(containsString("id=\"screenPlayerNextEpBtn\"")))
                .andExpect(content().string(containsString("id=\"screenPlayerNextUpPrompt\"")))
                // SERIES synopsis label
                .andExpect(content().string(containsString("Tóm tắt tập</h2>")))
                // Navigation controls
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1/watch/tap-1\"")))
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1/watch/tap-3\"")))
                // Unboxed playlist header & count
                .andExpect(content().string(containsString("class=\"screen-watch-playlist-title\"")))
                .andExpect(content().string(containsString("Danh sách tập")))
                .andExpect(content().string(containsString("class=\"screen-watch-playlist-count\"")))
                .andExpect(content().string(containsString("3 tập")))
                // Playlist chips with current episode marked and 3-column episode layout
                .andExpect(content().string(containsString("screen-watch-grid--episodes")))
                .andExpect(content().string(containsString("class=\"screen-episode-chip is-current\"")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                // Discussion / Comments section integration
                .andExpect(content().string(containsString("id=\"screenDiscussion\"")))
                .andExpect(content().string(containsString("data-title-id=\"" + titleId + "\"")))
                .andExpect(content().string(containsString("data-entry-id=\"" + entryId + "\"")))
                .andExpect(content().string(containsString("href=\"/css/shared/comments.css\"")))
                .andExpect(content().string(containsString("src=\"/js/screen/screen-comments.js\"")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Unplayable watch stage renders episode thumbnail image when present")
    void shouldRenderUnavailableStageWithThumbnailImage() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID thumbnailId = UUID.randomUUID();

        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, entryId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Xuất Thế", null, null, thumbnailId,
                false, null, null,
                null, null, List.of()
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-1", "tap-1")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-1/watch/tap-1"))
                .andExpect(status().isOk())
                // Visual stage backdrop image rendered with thumbnail variant URL and content fallback
                .andExpect(content().string(containsString("screen-watch-stage-backdrop-img")))
                .andExpect(content().string(containsString("src=\"/media/assets/" + thumbnailId + "/variants/w600\"")))
                .andExpect(content().string(containsString("data-fallback-src=\"/media/assets/" + thumbnailId + "/content\"")))
                .andExpect(content().string(containsString("onerror=\"if (!this.getAttribute('data-tried-fallback')")))
                // Compact unavailable notice rendered over the stage
                .andExpect(content().string(containsString("Video chưa sẵn sàng")))
                .andExpect(content().string(containsString("Tập phim này hiện chưa có video phát lại")))
                // Native video element and player error overlay NOT rendered
                .andExpect(content().string(not(containsString("<video controls"))))
                .andExpect(content().string(not(containsString("screen-watch-player-error"))))
                .andExpect(content().string(not(containsString("id=\"screenPlayerError\""))))
                .andExpect(content().string(not(containsString("id=\"screenPlayerControls\""))))
                .andExpect(content().string(not(containsString("id=\"screenPlayerResumeModal\""))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Unplayable watch stage falls back to title landscape backdrop when episode thumbnail is missing")
    void shouldRenderUnavailableStageWithTitleBackdropWhenThumbnailIsMissing() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();

        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, backdropId, entryId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Xuất Thế", null, null, null,
                false, null, null,
                null, null, List.of()
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-1", "tap-1")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-1/watch/tap-1"))
                .andExpect(status().isOk())
                // Visual stage backdrop image rendered with backdrop variant w1200 URL and fallback
                .andExpect(content().string(containsString("screen-watch-stage-backdrop-img")))
                .andExpect(content().string(containsString("src=\"/media/assets/" + backdropId + "/variants/w1200\"")))
                .andExpect(content().string(containsString("data-fallback-src=\"/media/assets/" + backdropId + "/content\"")))
                .andExpect(content().string(containsString("screen-watch-stage-foreground-img--landscape")))
                // Ensure small portrait poster is NOT rendered over the landscape backdrop
                .andExpect(content().string(not(containsString("/media/assets/" + posterId))))
                .andExpect(content().string(containsString("onerror=\"if (!this.getAttribute('data-tried-fallback')")))
                .andExpect(content().string(containsString("Video chưa sẵn sàng")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Unplayable watch stage falls back to title poster image when episode thumbnail and backdrop are both missing")
    void shouldRenderUnavailableStageWithTitlePosterWhenThumbnailIsMissing() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();

        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, entryId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Xuất Thế", null, null, null,
                false, null, null,
                null, null, List.of()
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-1", "tap-1")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-1/watch/tap-1"))
                .andExpect(status().isOk())
                // Visual stage backdrop image rendered with poster variant URL
                .andExpect(content().string(containsString("screen-watch-stage-backdrop-img")))
                .andExpect(content().string(containsString("src=\"/media/assets/" + posterId + "/variants/w600\"")))
                .andExpect(content().string(containsString("data-fallback-src=\"/media/assets/" + posterId + "/content\"")))
                .andExpect(content().string(containsString("screen-watch-stage-foreground-img--portrait")))
                .andExpect(content().string(containsString("onerror=\"if (!this.getAttribute('data-tried-fallback')")))
                .andExpect(content().string(containsString("Video chưa sẵn sàng")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Renders unavailable card when content is not playable")
    void shouldRenderUnavailableCardWhenNotPlayable() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, entryId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Xuất Thế", null, null, null,
                false, null, null,
                null, null, List.of()
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-1", "tap-1")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-1/watch/tap-1"))
                .andExpect(status().isOk())
                // Video unavailable card rendered with episode-specific wording
                .andExpect(content().string(containsString("Video chưa sẵn sàng")))
                .andExpect(content().string(containsString("Tập phim này hiện chưa có video phát lại")))
                // Native video element NOT rendered
                .andExpect(content().string(not(containsString("<video controls"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: FEATURE watch page renders generic non-episodic terminology and truthful video/webm MIME")
    void shouldRenderFeatureWatchPageWithGenericTerminology() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        UUID videoAssetId = UUID.randomUUID();

        PublicScreenEntryDTO feat = new PublicScreenEntryDTO(
                entryId, "ban-chieu-chinh", ScreenEntryType.FEATURE, null,
                "Bản Chiếu Rạp", "Tóm tắt phim điện ảnh chiếu rạp.", 7200L, null, true
        );
        PublicScreenEntryDTO tr = new PublicScreenEntryDTO(
                UUID.randomUUID(), "trailer-1", ScreenEntryType.TRAILER, 1,
                "Trailer 1", null, 120L, null, true
        );

        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-movie", "Kiếm Lai Điện Ảnh",
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, entryId, "ban-chieu-chinh", ScreenEntryType.FEATURE, null,
                "Bản Chiếu Rạp", "Tóm tắt phim điện ảnh chiếu rạp.", 7200L, null,
                true, "/media/assets/" + videoAssetId + "/content", "video/mp4",
                null, null, List.of(feat, tr)
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-movie", "ban-chieu-chinh")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-movie/watch/ban-chieu-chinh"))
                .andExpect(status().isOk())
                // Compact now-playing heading above the stage
                .andExpect(content().string(containsString("class=\"screen-watch-now-playing-title\"")))
                .andExpect(content().string(containsString("Kiếm Lai Điện Ảnh · Bản chiếu chính")))
                // Truthful canonical video/mp4 MIME type rendered in source tag
                .andExpect(content().string(containsString("type=\"video/mp4\"")))
                // Generic non-episodic labels
                .andExpect(content().string(containsString("Bản chiếu chính")))
                .andExpect(content().string(containsString("Tóm tắt</h2>")))
                .andExpect(content().string(not(containsString("Tóm tắt tập</h2>"))))
                // Generic playlist section label & flexible grid
                .andExpect(content().string(containsString("Bản phát hành &amp; Video")))
                .andExpect(content().string(containsString("screen-watch-grid--flexible")))
                // No episode previous/next navigation
                .andExpect(content().string(not(containsString("screen-watch-episode-nav"))))
                .andExpect(content().string(not(containsString("id=\"screenPlayerNextEpBtn\""))))
                .andExpect(content().string(not(containsString("id=\"screenPlayerNextUpPrompt\""))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Unplayable FEATURE watch page renders generic non-episodic unavailable message")
    void shouldRenderUnplayableFeatureWithGenericUnavailableMessage() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        PublicScreenWatchDTO watchDTO = new PublicScreenWatchDTO(
                titleId, "kiem-lai-movie", "Kiếm Lai Điện Ảnh",
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, entryId, "ban-chieu-chinh", ScreenEntryType.FEATURE, null,
                "Bản Chiếu Rạp", null, 7200L, null,
                false, null, null,
                null, null, List.of()
        );

        when(getPublicScreenWatchUseCase.execute("kiem-lai-movie", "ban-chieu-chinh")).thenReturn(watchDTO);

        mockMvc.perform(get("/screen/kiem-lai-movie/watch/ban-chieu-chinh"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Video chưa sẵn sàng")))
                .andExpect(content().string(containsString("Nội dung này hiện chưa có video phát lại")))
                .andExpect(content().string(not(containsString("Tập phim này hiện chưa có video phát lại"))));
    }
}
