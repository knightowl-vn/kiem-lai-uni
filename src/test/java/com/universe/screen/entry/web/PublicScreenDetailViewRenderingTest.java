package com.universe.screen.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenEntryDetailDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseInstallmentDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import com.universe.screen.application.usecase.GetPublicScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenTitleDetailUseCase;
import com.universe.screen.domain.ScreenAiringStatus;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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
        "security.remember-me.key=test-secret-key-screen-detail-rendering-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicScreenDetailViewRendering SSR Tests (Thymeleaf)")
class PublicScreenDetailViewRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GetPublicScreenCatalogUseCase getPublicScreenCatalogUseCase;

    @MockBean
    private GetPublicScreenTitleDetailUseCase getPublicScreenTitleDetailUseCase;

    @MockBean
    private GetPublicScreenEntryDetailUseCase getPublicScreenEntryDetailUseCase;

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

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

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
    @DisplayName("SSR: Renders redesigned SERIES detail page with classification, badges, labeled metadata, actions, tabs, and shared comments")
    void shouldRenderRedesignedSeriesDetailPage() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();

        PublicScreenFranchiseDTO franchise = new PublicScreenFranchiseDTO(
                UUID.randomUUID(), "Kiếm Lai", "kiem-lai",
                List.of(
                        new PublicScreenFranchiseInstallmentDTO(titleId, "kiem-lai-1", "Kiếm Lai Phần 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, true),
                        new PublicScreenFranchiseInstallmentDTO(UUID.randomUUID(), "kiem-lai-2", "Kiếm Lai Phần 2", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, false)
                )
        );

        PublicScreenEntryDTO ep1 = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, 1440L, null, true
        );
        PublicScreenEntryDTO ep2 = new PublicScreenEntryDTO(
                UUID.randomUUID(), "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null, 1440L, null, true
        );

        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", "剑来 第一季",
                "Thiếu niên Trần Bình An quật khởi từ Ly Châu động thiên.",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                posterId, null, now, 2, 24,
                List.of(ep1, ep2), List.of(franchise),
                ScreenAiringStatus.ONGOING, 2024, "Trung Quốc", "Phong Hỏa Hí Chư Hầu", "Đạo diễn A", "Sparkly Key Animation", 24
        );

        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-1")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-1"))
                .andExpect(status().isOk())
                // Header & Breadcrumb
                .andExpect(content().string(containsString("Quay lại danh mục phim")))
                .andExpect(content().string(containsString("href=\"/screen\"")))
                // Title & Original Title
                .andExpect(content().string(containsString("Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("剑来 第一季")))
                // Structure & Presentation: Badges
                .andExpect(content().string(containsString("Phim bộ")))
                .andExpect(content().string(containsString("Hoạt hình 3D")))
                // Badges: Airing, Year, Episode Progress
                .andExpect(content().string(containsString("Đang chiếu")))
                .andExpect(content().string(containsString("2024")))
                .andExpect(content().string(containsString("Tập 2/24")))
                // Media poster URLs
                .andExpect(content().string(containsString("/media/assets/" + posterId + "/variants/w300")))
                .andExpect(content().string(containsString("/media/assets/" + posterId + "/content")))
                // Detailed metadata list (2-column grid)
                .andExpect(content().string(containsString("Thời lượng:")))
                .andExpect(content().string(containsString("~24 phút/tập")))
                .andExpect(content().string(containsString("Quốc gia:")))
                .andExpect(content().string(containsString("Trung Quốc")))
                .andExpect(content().string(containsString("Nguyên tác:")))
                .andExpect(content().string(containsString("Phong Hỏa Hí Chư Hầu")))
                .andExpect(content().string(containsString("Năm phát hành:")))
                .andExpect(content().string(containsString("Đạo diễn:")))
                .andExpect(content().string(containsString("Đạo diễn A")))
                .andExpect(content().string(containsString("Đơn vị sản xuất:")))
                .andExpect(content().string(containsString("Sparkly Key Animation")))
                // Synopsis
                .andExpect(content().string(containsString("Tóm tắt")))
                .andExpect(content().string(containsString("Thiếu niên Trần Bình An quật khởi")))
                // Action area: Watch now selects earliest published episode + Follow action disabled
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1/entries/tap-1\"")))
                .andExpect(content().string(containsString("Xem ngay")))
                .andExpect(content().string(containsString("Theo dõi")))
                // Franchise section
                .andExpect(content().string(containsString("Các phần liên kết")))
                .andExpect(content().string(containsString("Kiếm Lai</strong>")))
                .andExpect(content().string(containsString("<nav class=\"screen-franchise-nav\"")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString("Đang xem")))
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-2\"")))
                // Tabs: Episodes and Characters
                .andExpect(content().string(containsString("Danh sách tập")))
                .andExpect(content().string(containsString("Nhân vật")))
                .andExpect(content().string(containsString("id=\"tab-episodes\"")))
                .andExpect(content().string(containsString("id=\"tab-characters\"")))
                .andExpect(content().string(containsString("id=\"panel-episodes\"")))
                .andExpect(content().string(containsString("id=\"panel-characters\"")))
                // Compact episode chips in episodes tab
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1/entries/tap-1\"")))
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1/entries/tap-2\"")))
                // Character tab honest empty state
                .andExpect(content().string(containsString("Thông tin nhân vật đang được cập nhật")))
                // Shared Comments Section outside tabs
                .andExpect(content().string(containsString("id=\"screenDiscussion\"")))
                .andExpect(content().string(containsString("id=\"screenDiscussionHeading\"")))
                .andExpect(content().string(containsString("Bình luận")))
                .andExpect(content().string(containsString("id=\"screenRootComposer\"")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"")))
                .andExpect(content().string(containsString("data-csrf-header=\"X-CSRF-TOKEN\"")))
                .andExpect(content().string(containsString("data-csrf-token=")))
                // Boundary: No video player in title detail
                .andExpect(content().string(not(containsString("<video"))))
                .andExpect(content().string(not(containsString("<iframe"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Renders FEATURE detail page with classification, feature badge, and single-value duration")
    void shouldRenderFeatureDetailPageWithFeatureDurationAndReleasedStatus() throws Exception {
        UUID titleId = UUID.randomUUID();

        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                titleId, "kiem-lai-movie", "Kiếm Lai Điện Ảnh", null,
                "Phim điện ảnh Kiếm Lai.", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null,
                List.of(), null,
                ScreenAiringStatus.RELEASED, 2023, "Trung Quốc", "Phong Hỏa Hí Chư Hầu", null, null, 120
        );

        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-movie")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-movie"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kiếm Lai Điện Ảnh")))
                .andExpect(content().string(containsString("Phim lẻ")))
                .andExpect(content().string(containsString("Hoạt hình 3D")))
                .andExpect(content().string(containsString("Đã phát hành")))
                .andExpect(content().string(containsString("2023")))
                .andExpect(content().string(containsString("Thời lượng:")))
                .andExpect(content().string(containsString("120 phút")))
                .andExpect(content().string(containsString("Quốc gia:")))
                .andExpect(content().string(containsString("Trung Quốc")))
                .andExpect(content().string(containsString("Năm phát hành:")))
                // Boundary: Director and Studio not rendered when null
                .andExpect(content().string(not(containsString("Đạo diễn:"))))
                .andExpect(content().string(not(containsString("Đơn vị sản xuất:"))))
                // Boundary: Episode progress badge suppressed for FEATURE
                .andExpect(content().string(not(containsString("Tập 1/"))))
                // No franchise section
                .andExpect(content().string(not(containsString("screen-franchise-section"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Gracefully omits metadata list when all optional metadata is null")
    void shouldOmitMetadataListWhenAllOptionalMetadataIsNull() throws Exception {
        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-minimal", "Kiếm Lai Tối Giản", null,
                null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null,
                List.of(), null,
                null, null, null, null, null, null, null
        );

        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-minimal")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-minimal"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kiếm Lai Tối Giản")))
                .andExpect(content().string(containsString("Chưa có phần tóm tắt nội dung")))
                // Metadata list is omitted
                .andExpect(content().string(not(containsString("screen-detail-metadata-list"))))
                // Airing status badge is omitted
                .andExpect(content().string(not(containsString("screen-badge--airing"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Nonexistent/unpublished title returns HTTP 404 with not-found template")
    void shouldRenderNotFoundPageForUnknownSlug() throws Exception {
        when(getPublicScreenTitleDetailUseCase.execute("unknown-slug"))
                .thenThrow(new PublishedScreenTitleNotFoundException("unknown-slug"));

        mockMvc.perform(get("/screen/unknown-slug"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Tác phẩm không tồn tại")))
                .andExpect(content().string(containsString("Tác phẩm bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản.")))
                .andExpect(content().string(containsString("href=\"/screen\"")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Renders multiple franchise navigation sections with proper accessibility and current markers")
    void shouldRenderMultipleFranchiseNavigationSections() throws Exception {
        UUID currentTitleId = UUID.randomUUID();
        UUID sibling1Id = UUID.randomUUID();
        UUID sibling2Id = UUID.randomUUID();

        PublicScreenFranchiseDTO f1 = new PublicScreenFranchiseDTO(
                UUID.randomUUID(), "Vũ Trụ Kiếm Lai", "kiem-lai-universe",
                List.of(
                        new PublicScreenFranchiseInstallmentDTO(sibling1Id, "kiem-lai-tien-truyen", "Tiền Truyện", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, false),
                        new PublicScreenFranchiseInstallmentDTO(currentTitleId, "kiem-lai-movie-2", "Phim Chiếu Rạp 2", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, true)
                )
        );

        PublicScreenFranchiseDTO f2 = new PublicScreenFranchiseDTO(
                UUID.randomUUID(), "Tuyển Tập Ngoại Truyện", "tuyen-tap-ngoai-truyen",
                List.of(
                        new PublicScreenFranchiseInstallmentDTO(currentTitleId, "kiem-lai-movie-2", "Phim Chiếu Rạp 2", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, true),
                        new PublicScreenFranchiseInstallmentDTO(sibling2Id, "ngoai-truyen-doc-lap", "Ngoại Truyện Độc Lập", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, false)
                )
        );

        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                currentTitleId, "kiem-lai-movie-2", "Phim Chiếu Rạp 2", null,
                "Thuộc nhiều franchise.", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null,
                List.of(), List.of(f1, f2)
        );

        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-movie-2")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-movie-2"))
                .andExpect(status().isOk())
                // Both franchise names present
                .andExpect(content().string(containsString("Vũ Trụ Kiếm Lai")))
                .andExpect(content().string(containsString("Tuyển Tập Ngoại Truyện")))
                // Semantic nav elements
                .andExpect(content().string(containsString("aria-label=\"Danh sách phần phim trong Vũ Trụ Kiếm Lai\"")))
                .andExpect(content().string(containsString("aria-label=\"Danh sách phần phim trong Tuyển Tập Ngoại Truyện\"")))
                // Sibling links
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-tien-truyen\"")))
                .andExpect(content().string(containsString("href=\"/screen/ngoai-truyen-doc-lap\"")))
                // Current item marked with aria-current="page" and badge
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString("Đang xem")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("SSR: Renders public entry detail route with parent breadcrumb, duration, and transparent MS-08G video notice")
    void shouldRenderEntryDetailPageWithTransparentPlaybackNotice() throws Exception {
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        PublicScreenEntryDetailDTO entryDetail = new PublicScreenEntryDetailDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", ScreenStructure.SERIES,
                entryId, "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Xuất Thế", "Nội dung tóm tắt của tập 1.",
                1440L, null, false
        );

        when(getPublicScreenEntryDetailUseCase.execute("kiem-lai-1", "tap-1")).thenReturn(entryDetail);

        mockMvc.perform(get("/screen/kiem-lai-1/entries/tap-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quay lại tác phẩm:")))
                .andExpect(content().string(containsString("Kiếm Lai Phần 1")))
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-1\"")))
                .andExpect(content().string(containsString("Tập 1: Xuất Thế")))
                .andExpect(content().string(containsString("24:00")))
                .andExpect(content().string(containsString("Nội dung tóm tắt của tập 1.")))
                .andExpect(content().string(containsString("Trình phát video đang được hoàn thiện")))
                .andExpect(content().string(containsString("MS-08G")))
                // Comments & CSRF attributes on entry detail
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"")))
                .andExpect(content().string(containsString("id=\"screenDiscussion\"")))
                .andExpect(content().string(containsString("data-csrf-header=\"X-CSRF-TOKEN\"")))
                .andExpect(content().string(containsString("data-csrf-token=")));
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = "USER")
    @DisplayName("SSR: Authenticated user with fresh session renders detail page safely without SpelEvaluationException / IllegalStateException")
    void shouldRenderSeriesDetailPageForAuthenticatedUserWithFreshSessionWithoutCsrfException() throws Exception {
        UUID titleId = UUID.randomUUID();

        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                titleId, "kiem-lai-1", "Kiếm Lai Phần 1", "剑来 第一季",
                "Thiếu niên Trần Bình An quật khởi từ Ly Châu động thiên.",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, now, 1, 24,
                List.of(), List.of(),
                ScreenAiringStatus.ONGOING, 2024, "Trung Quốc", "Phong Hỏa Hí Chư Hầu", "Đạo diễn A", "Sparkly Key Animation", 24
        );

        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-1")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<meta name=\"_csrf\"")))
                .andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\"")))
                .andExpect(content().string(containsString("id=\"screenDiscussion\"")))
                .andExpect(content().string(containsString("data-csrf-header=\"X-CSRF-TOKEN\"")))
                .andExpect(content().string(containsString("data-csrf-token=")))
                .andExpect(content().string(containsString("data-authenticated=\"true\"")));
    }
}
