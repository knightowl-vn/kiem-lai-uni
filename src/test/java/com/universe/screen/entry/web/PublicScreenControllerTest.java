package com.universe.screen.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.dto.PublicScreenEntryDetailDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import com.universe.screen.application.usecase.GetPublicScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenTitleDetailUseCase;
import com.universe.screen.domain.ScreenEntryType;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = PublicScreenController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-screen-1234567890",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicScreenController WebMvc Tests")
class PublicScreenControllerTest {

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
    @DisplayName("Guest GET /screen returns 200 OK with catalog model and view name")
    void guestGetScreenReturns200WithCatalogView() throws Exception {
        PublicScreenCatalogItemDTO item = new PublicScreenCatalogItemDTO(
                "kiem-lai-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Hành trình thiếu niên",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                UUID.randomUUID(),
                now
        );

        PublicScreenCatalogPageDTO catalogPage = new PublicScreenCatalogPageDTO(
                List.of(item),
                0,
                12,
                1L,
                1,
                true,
                true
        );

        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(catalogPage);

        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/catalog"))
                .andExpect(model().attributeExists("catalogPage"))
                .andExpect(model().attribute("activeNav", equalTo("screen")))
                .andExpect(model().attribute("pageTitle", equalTo("Phim - Kiếm Lai Universe")))
                .andExpect(model().attribute("catalogPage", equalTo(PublicScreenCatalogPageView.fromDTO(catalogPage))));

        verify(getPublicScreenCatalogUseCase).execute(0, 12);
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = "USER")
    @DisplayName("Authenticated user GET /screen returns 200 OK")
    void authenticatedUserGetScreenReturns200() throws Exception {
        PublicScreenCatalogPageDTO emptyPage = new PublicScreenCatalogPageDTO(
                List.of(), 0, 12, 0L, 0, true, true
        );
        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(emptyPage);

        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/catalog"))
                .andExpect(model().attribute("activeNav", equalTo("screen")));

        verify(getPublicScreenCatalogUseCase).execute(0, 12);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /screen with page and size query params delegates to use case")
    void getScreenWithPaginationParamsDelegatesToUseCase() throws Exception {
        PublicScreenCatalogPageDTO pagedResult = new PublicScreenCatalogPageDTO(
                List.of(), 2, 24, 50L, 3, false, false
        );
        when(getPublicScreenCatalogUseCase.execute(2, 24)).thenReturn(pagedResult);

        mockMvc.perform(get("/screen").param("page", "2").param("size", "24"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/catalog"))
                .andExpect(model().attribute("catalogPage", equalTo(PublicScreenCatalogPageView.fromDTO(pagedResult))));

        verify(getPublicScreenCatalogUseCase).execute(2, 24);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /screen/ with trailing slash returns 200 OK")
    void getScreenWithTrailingSlashReturns200() throws Exception {
        PublicScreenCatalogPageDTO emptyPage = new PublicScreenCatalogPageDTO(
                List.of(), 0, 12, 0L, 0, true, true
        );
        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(emptyPage);

        mockMvc.perform(get("/screen/"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/catalog"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Empty catalog returns 200 OK and renders safely without error")
    void emptyCatalogReturns200Ok() throws Exception {
        PublicScreenCatalogPageDTO emptyPage = new PublicScreenCatalogPageDTO(
                List.of(), 0, 12, 0L, 0, true, true
        );
        when(getPublicScreenCatalogUseCase.execute(0, 12)).thenReturn(emptyPage);

        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/catalog"))
                .andExpect(model().attribute("catalogPage", equalTo(PublicScreenCatalogPageView.fromDTO(emptyPage))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Out of range requested page returns 200 OK with outOfRange page model")
    void outOfRangePageReturns200WithOutOfRangeModel() throws Exception {
        PublicScreenCatalogPageDTO outOfRangeDto = new PublicScreenCatalogPageDTO(
                List.of(), 10, 12, 15L, 2, false, true
        );
        when(getPublicScreenCatalogUseCase.execute(10, 12)).thenReturn(outOfRangeDto);

        mockMvc.perform(get("/screen").param("page", "10"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/catalog"))
                .andExpect(model().attribute("catalogPage", equalTo(PublicScreenCatalogPageView.fromDTO(outOfRangeDto))));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest GET /screen/{titleSlug} returns 200 OK with titleDetail model and view name")
    void guestGetTitleDetailReturns200WithDetailView() throws Exception {
        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai Phần 1", "剑来",
                "Mô tả phim", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                UUID.randomUUID(), null, now, 12, 24, List.of(), null
        );
        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-1")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/detail"))
                .andExpect(model().attribute("titleDetail", equalTo(PublicScreenTitleDetailView.fromDTO(detailDTO))))
                .andExpect(model().attribute("activeNav", "screen"))
                .andExpect(model().attribute("pageTitle", "Kiếm Lai Phần 1 - Kiếm Lai Universe"));

        verify(getPublicScreenTitleDetailUseCase).execute("kiem-lai-1");
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = "USER")
    @DisplayName("Authenticated USER GET /screen/{titleSlug} returns 200 OK")
    void authenticatedUserGetTitleDetailReturns200() throws Exception {
        PublicScreenTitleDetailDTO detailDTO = new PublicScreenTitleDetailDTO(
                UUID.randomUUID(), "kiem-lai-movie", "Kiếm Lai Điện Ảnh", null,
                null, ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D,
                null, null, now, null, null, List.of(), null
        );
        when(getPublicScreenTitleDetailUseCase.execute("kiem-lai-movie")).thenReturn(detailDTO);

        mockMvc.perform(get("/screen/kiem-lai-movie"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/detail"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Nonexistent or unpublished title slug returns 404 NOT FOUND with not-found view")
    void nonexistentTitleReturns404WithNotFoundView() throws Exception {
        when(getPublicScreenTitleDetailUseCase.execute("nonexistent-slug"))
                .thenThrow(new PublishedScreenTitleNotFoundException("nonexistent-slug"));

        mockMvc.perform(get("/screen/nonexistent-slug"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("screen/not-found"))
                .andExpect(model().attribute("errorTitle", "Tác phẩm không tồn tại"))
                .andExpect(model().attribute("errorMessage", "Tác phẩm bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản."))
                .andExpect(model().attribute("activeNav", "screen"))
                .andExpect(model().attribute("pageTitle", "Không tìm thấy tác phẩm - Kiếm Lai Universe"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Guest GET /screen/{titleSlug}/entries/{entrySlug} returns 200 OK with entryDetail model and view name")
    void guestGetEntryDetailReturns200WithEntryDetailView() throws Exception {
        PublicScreenEntryDetailDTO entryDetailDTO = new PublicScreenEntryDetailDTO(
                UUID.randomUUID(), "kiem-lai-1", "Kiếm Lai Phần 1", ScreenStructure.SERIES,
                UUID.randomUUID(), "tap-1", ScreenEntryType.EPISODE, 1,
                "Tập 1: Khởi đầu", "Tóm tắt tập 1", 1440L, UUID.randomUUID(), false
        );
        when(getPublicScreenEntryDetailUseCase.execute("kiem-lai-1", "tap-1")).thenReturn(entryDetailDTO);

        mockMvc.perform(get("/screen/kiem-lai-1/entries/tap-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/entry-detail"))
                .andExpect(model().attribute("entryDetail", equalTo(PublicScreenEntryDetailView.fromDTO(entryDetailDTO))))
                .andExpect(model().attribute("activeNav", "screen"))
                .andExpect(model().attribute("pageTitle", "Tập 1 - Kiếm Lai Phần 1 - Kiếm Lai Universe"));

        verify(getPublicScreenEntryDetailUseCase).execute("kiem-lai-1", "tap-1");
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = "USER")
    @DisplayName("Authenticated user GET /screen/{titleSlug}/entries/{entrySlug} returns 200 OK")
    void authenticatedUserGetEntryDetailReturns200() throws Exception {
        PublicScreenEntryDetailDTO entryDetailDTO = new PublicScreenEntryDetailDTO(
                UUID.randomUUID(), "kiem-lai-movie", "Kiếm Lai Điện Ảnh", ScreenStructure.FEATURE,
                UUID.randomUUID(), "ban-chieu-chinh", ScreenEntryType.FEATURE, null,
                "Bản Chiếu Chính", null, 7200L, null, false
        );
        when(getPublicScreenEntryDetailUseCase.execute("kiem-lai-movie", "ban-chieu-chinh")).thenReturn(entryDetailDTO);

        mockMvc.perform(get("/screen/kiem-lai-movie/entries/ban-chieu-chinh"))
                .andExpect(status().isOk())
                .andExpect(view().name("screen/entry-detail"));

        verify(getPublicScreenEntryDetailUseCase).execute("kiem-lai-movie", "ban-chieu-chinh");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Nonexistent or unpublished entry slug returns 404 NOT FOUND with not-found view")
    void nonexistentEntryReturns404WithNotFoundView() throws Exception {
        when(getPublicScreenEntryDetailUseCase.execute("kiem-lai-1", "nonexistent-entry"))
                .thenThrow(new PublishedScreenEntryNotFoundException("kiem-lai-1", "nonexistent-entry"));

        mockMvc.perform(get("/screen/kiem-lai-1/entries/nonexistent-entry"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("screen/not-found"))
                .andExpect(model().attribute("errorTitle", "Tập phim không tồn tại"))
                .andExpect(model().attribute("errorMessage", "Tập phim hoặc bản phát hành bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản."))
                .andExpect(model().attribute("activeNav", "screen"))
                .andExpect(model().attribute("pageTitle", "Không tìm thấy tập phim - Kiếm Lai Universe"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Nonexistent parent title slug for entry detail returns 404 NOT FOUND with not-found view")
    void nonexistentTitleForEntryDetailReturns404WithNotFoundView() throws Exception {
        when(getPublicScreenEntryDetailUseCase.execute("nonexistent-title", "tap-1"))
                .thenThrow(new PublishedScreenTitleNotFoundException("nonexistent-title"));

        mockMvc.perform(get("/screen/nonexistent-title/entries/tap-1"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("screen/not-found"))
                .andExpect(model().attribute("errorTitle", "Tác phẩm không tồn tại"))
                .andExpect(model().attribute("errorMessage", "Tác phẩm bạn đang tìm không tồn tại, đã bị gỡ hoặc chưa được xuất bản."));
    }
}
