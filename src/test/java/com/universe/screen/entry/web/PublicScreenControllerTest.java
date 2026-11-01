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
}
