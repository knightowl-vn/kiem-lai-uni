package com.universe.screen.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
import com.universe.screen.application.usecase.GetPublicScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetPublicScreenTitleDetailUseCase;
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
        "security.remember-me.key=test-secret-key-screen-navbar-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("Public Screen Navbar Integration Rendering Tests")
class PublicScreenNavbarRenderingTest {

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

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(accountStatusFilter).doFilter(any(), any(), any());

        when(getPublicScreenCatalogUseCase.execute(any(Integer.class), any(Integer.class)))
                .thenReturn(new PublicScreenCatalogPageDTO(List.of(), 0, 12, 0L, 0, true, true));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /screen renders navbar with Screen active and other destinations inactive")
    void screenCatalogRendersNavbarWithActiveScreenDestination() throws Exception {
        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk())
                // All top-level destinations exist
                .andExpect(content().string(containsString("href=\"/home\"")))
                .andExpect(content().string(containsString("href=\"/novel\"")))
                .andExpect(content().string(containsString("href=\"/wiki\"")))
                .andExpect(content().string(containsString("href=\"/screen\"")))
                .andExpect(content().string(containsString("href=\"/community\"")))
                // Screen destination text and class
                .andExpect(content().string(containsString("navbar-screen-link")))
                .andExpect(content().string(containsString("Phim")))
                // Screen active status
                .andExpect(content().string(containsString("navbar-screen-link  active")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                // Inactive links do not have active class
                .andExpect(content().string(not(containsString("navbar-home-link  active"))))
                .andExpect(content().string(not(containsString("navbar-novel-link  active"))))
                .andExpect(content().string(not(containsString("navbar-wiki-link  active"))))
                .andExpect(content().string(not(containsString("navbar-community-link  active"))));
    }
}
