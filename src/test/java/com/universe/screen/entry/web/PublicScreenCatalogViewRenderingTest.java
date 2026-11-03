package com.universe.screen.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import com.universe.screen.application.usecase.GetPublicScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenTitleDetailUseCase;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
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
        "security.remember-me.key=test-secret-key-screen-rendering-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicScreenCatalogViewRendering Tests (Thymeleaf SSR)")
class PublicScreenCatalogViewRenderingTest {

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
    @DisplayName("Renders minimalist catalog cards with title, poster, episode badge and no visual clutter")
    void shouldRenderCatalogCardsWithBadgesAndPosterUrls() throws Exception {
        UUID posterId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        PublicScreenCatalogItemDTO item1 = new PublicScreenCatalogItemDTO(
                "kiem-lai-donghua-1",
                "Kiếm Lai Hoạt Hình Phần 1",
                "The Great Ruler",
                "Trần Bình An bắt đầu bước vào con đường tu tiên.",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                now,
                12,
                24
        );
        PublicScreenCatalogItemDTO item2 = new PublicScreenCatalogItemDTO(
                "kiem-lai-movie-1",
                "Kiếm Lai Điện Ảnh 1",
                null,
                null,
                ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION,
                null,
                now,
                null,
                null
        );

        PublicScreenCatalogPageDTO catalogPage = new PublicScreenCatalogPageDTO(
                List.of(item1, item2),
                0,
                12,
                2L,
                1,
                true,
                true
        );

        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(catalogPage);

        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                // Header (no eyebrow)
                .andExpect(content().string(containsString("Danh mục phim")))
                .andExpect(content().string(not(containsString("KIẾM LAI SCREEN"))))
                // Card 1
                .andExpect(content().string(containsString("Kiếm Lai Hoạt Hình Phần 1")))
                .andExpect(content().string(containsString("The Great Ruler")))
                .andExpect(content().string(containsString("screen-card-original-title")))
                .andExpect(content().string(containsString("Tập 12/24")))
                .andExpect(content().string(containsString("/media/assets/" + posterId + "/variants/w300")))
                .andExpect(content().string(containsString("/media/assets/" + posterId + "/content")))
                // Card 2
                .andExpect(content().string(containsString("Kiếm Lai Điện Ảnh 1")))
                .andExpect(content().string(containsString("Phim lẻ")))
                // Poster placeholder rendered for item2
                .andExpect(content().string(containsString("screen-card-poster-placeholder")))
                // Card is rendered as semantic article
                .andExpect(content().string(containsString("screen-card")))
                .andExpect(content().string(containsString("screen-card-episode-badge")))
                // Title inner text span for overflow/marquee support and tooltip accessibility
                .andExpect(content().string(containsString("screen-card-title-text")))
                .andExpect(content().string(containsString("title=\"Kiếm Lai Hoạt Hình Phần 1\"")))
                .andExpect(content().string(containsString("title=\"The Great Ruler\"")))
                // Screen catalog script include
                .andExpect(content().string(containsString("/js/screen/screen-catalog.js")))
                // Minimalist UI: no synopsis, no category badges panel
                .andExpect(content().string(not(containsString("screen-card-synopsis"))))
                .andExpect(content().string(not(containsString("screen-card-badges"))))
                .andExpect(content().string(not(containsString("Trần Bình An bắt đầu bước vào con đường tu tiên."))))
                .andExpect(content().string(not(containsString("Hoạt hình 3D"))))
                .andExpect(content().string(not(containsString("Người đóng"))))
                // MS-08F2: Semantic detail links emitted for catalog cards
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-donghua-1\"")))
                .andExpect(content().string(containsString("href=\"/screen/kiem-lai-movie-1\"")))
                // Neither empty state nor out-of-range state is rendered
                .andExpect(content().string(not(containsString("screen-empty-state"))))
                .andExpect(content().string(not(containsString("screen-out-of-range-state"))))
                // In single-page mode, pagination nav is not displayed
                .andExpect(content().string(not(containsString("screen-pagination"))))
                // Hard scope: no video players, no internal bucket/storage provider leakage
                .andExpect(content().string(not(containsString("<video"))))
                .andExpect(content().string(not(containsString("backblaze"))))
                .andExpect(content().string(not(containsString("b2-"))))
                .andExpect(content().string(not(containsString("r2-"))))
                .andExpect(content().string(not(containsString("cloudflare"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Renders empty state when catalog has no published titles")
    void shouldRenderEmptyStateWhenNoPublishedTitles() throws Exception {
        PublicScreenCatalogPageDTO emptyPage = new PublicScreenCatalogPageDTO(
                List.of(), 0, 12, 0L, 0, true, true
        );
        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(emptyPage);

        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("screen-empty-state")))
                .andExpect(content().string(containsString("Chưa có tác phẩm nào")))
                .andExpect(content().string(containsString("Hiện tại chưa có phim hoặc hoạt hình nào được xuất bản.")))
                .andExpect(content().string(not(containsString("screen-out-of-range-state"))))
                .andExpect(content().string(not(containsString("screen-card"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Corrective 2: Renders out-of-range state with back link when requested page is empty but catalog has titles")
    void shouldRenderOutOfRangeStateWhenRequestedPageIsEmptyButCatalogHasTitles() throws Exception {
        PublicScreenCatalogPageDTO outOfRangePage = new PublicScreenCatalogPageDTO(
                List.of(), 5, 12, 24L, 2, false, true
        );
        when(getPublicScreenCatalogUseCase.execute(5, 12)).thenReturn(outOfRangePage);

        mockMvc.perform(get("/screen").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("screen-out-of-range-state")))
                .andExpect(content().string(containsString("Trang không có nội dung")))
                .andExpect(content().string(containsString("Trang bạn yêu cầu không chứa tác phẩm nào hoặc vượt quá số trang hiện có.")))
                .andExpect(content().string(containsString("screen-out-of-range-btn")))
                .andExpect(content().string(containsString("Quay lại danh mục")))
                .andExpect(content().string(containsString("href=\"/screen\"")))
                .andExpect(content().string(not(containsString("screen-pagination"))))
                .andExpect(content().string(not(containsString("screen-empty-state"))))
                .andExpect(content().string(not(containsString("Chưa có tác phẩm nào"))))
                .andExpect(content().string(not(containsString("screen-card"))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Safely escapes special characters and long Vietnamese titles")
    void shouldSafelyEscapeSpecialCharactersInTitle() throws Exception {
        PublicScreenCatalogItemDTO item = new PublicScreenCatalogItemDTO(
                "title-with-script",
                "Kiếm Lai <script>alert('xss')</script> & Đặc Biệt",
                null,
                "Mô tả có ký tự & và <tag>",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                now
        );

        PublicScreenCatalogPageDTO catalogPage = new PublicScreenCatalogPageDTO(
                List.of(item), 0, 12, 1L, 1, true, true
        );
        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(catalogPage);

        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;")));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Renders pagination controls across multiple pages with correct disabled states")
    void shouldRenderPaginationControlsAcrossMultiplePages() throws Exception {
        PublicScreenCatalogItemDTO item = new PublicScreenCatalogItemDTO(
                "item-1", "Tác phẩm 1", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, now
        );

        // First page of 3
        PublicScreenCatalogPageDTO page0 = new PublicScreenCatalogPageDTO(
                List.of(item), 0, 12, 36L, 3, true, false
        );
        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(page0);

        mockMvc.perform(get("/screen").param("page", "0").param("size", "12"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("screen-pagination")))
                .andExpect(content().string(containsString("Trang <strong>1</strong> / <strong>3</strong>")))
                .andExpect(content().string(containsString("is-disabled"))) // previous is disabled
                .andExpect(content().string(containsString("href=\"/screen?page=1&amp;size=12\""))); // next is enabled

        // Middle page of 3
        PublicScreenCatalogPageDTO page1 = new PublicScreenCatalogPageDTO(
                List.of(item), 1, 12, 36L, 3, false, false
        );
        when(getPublicScreenCatalogUseCase.execute(1, 12)).thenReturn(page1);

        mockMvc.perform(get("/screen").param("page", "1").param("size", "12"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Trang <strong>2</strong> / <strong>3</strong>")))
                .andExpect(content().string(containsString("href=\"/screen?page=0&amp;size=12\""))) // previous enabled
                .andExpect(content().string(containsString("href=\"/screen?page=2&amp;size=12\""))); // next enabled

        // Last page of 3
        PublicScreenCatalogPageDTO page2 = new PublicScreenCatalogPageDTO(
                List.of(item), 2, 12, 36L, 3, false, true
        );
        when(getPublicScreenCatalogUseCase.execute(2, 12)).thenReturn(page2);

        mockMvc.perform(get("/screen").param("page", "2").param("size", "12"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Trang <strong>3</strong> / <strong>3</strong>")))
                .andExpect(content().string(containsString("href=\"/screen?page=1&amp;size=12\""))) // previous enabled
                .andExpect(content().string(containsString("is-disabled"))); // next is disabled
    }
}
