package com.universe.screen.entry.web;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.shared.security.AuthenticatedEmailResolver;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.usecase.GetPublicScreenCatalogUseCase;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicScreenController.class)
@Import(SecurityBeanConfig.class)
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-screen-security-12345",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("Public Screen Route Security Integration Tests")
class PublicScreenSecurityIntegrationTest {

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
    @DisplayName("Security: Anonymous guest can access GET /screen without redirecting to login")
    void anonymousGuestCanAccessGetScreen() throws Exception {
        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = "USER")
    @DisplayName("Security: Authenticated USER can access GET /screen")
    void authenticatedUserCanAccessGetScreen() throws Exception {
        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = "ADMIN")
    @DisplayName("Security: Authenticated ADMIN can access GET /screen")
    void authenticatedAdminCanAccessGetScreen() throws Exception {
        mockMvc.perform(get("/screen"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("Security: Anonymous mutation POST /screen is rejected (redirects to login)")
    void anonymousPostScreenIsRejected() throws Exception {
        mockMvc.perform(post("/screen").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "user@example.com", roles = "USER")
    @DisplayName("Security: Authenticated mutation POST /screen is rejected (read-only catalog)")
    void authenticatedPostScreenIsRejected() throws Exception {
        mockMvc.perform(post("/screen").with(csrf()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "user@example.com", roles = "USER")
    @DisplayName("Security: PUT /screen is rejected")
    void authenticatedPutScreenIsRejected() throws Exception {
        mockMvc.perform(put("/screen").with(csrf()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "user@example.com", roles = "USER")
    @DisplayName("Security: DELETE /screen is rejected")
    void authenticatedDeleteScreenIsRejected() throws Exception {
        mockMvc.perform(delete("/screen").with(csrf()))
                .andExpect(status().is4xxClientError());
    }
}
