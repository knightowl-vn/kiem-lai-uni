package com.universe.novel.entry.reader;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.novel.application.narration.GetPublicChapterNarrationManifestQuery;
import com.universe.novel.application.narration.GetPublicChapterNarrationManifestUseCase;
import com.universe.novel.contracts.dto.narration.PublicChapterNarrationManifestDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationSegmentDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicNovelChapterNarrationManifestController.class)
@Import({SecurityBeanConfig.class, PublicNovelChapterNarrationManifestSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("PublicNovelChapterNarrationManifestController Security Integration Tests (MS-04.9H.7A, MS-04.9H.7A1)")
class PublicNovelChapterNarrationManifestSecurityIntegrationTest {

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public GoogleOAuthSuccessHandler googleOAuthSuccessHandler() {
            return new GoogleOAuthSuccessHandler(Mockito.mock(GoogleOAuthUserService.class));
        }

        @Bean
        public CustomAuthenticationFailureHandler authenticationFailureHandler() {
            return new CustomAuthenticationFailureHandler();
        }

        @Bean
        public AccountStatusFilter accountStatusFilter(SpringDataUserJpaRepository userRepository) {
            return new AccountStatusFilter(
                    com.universe.identity.infrastructure.security.AccountStatusFilterTestSupport
                            .queryPort(userRepository)
            );
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpringDataUserJpaRepository springDataUserJpaRepository;

    @MockBean
    private GetPublicChapterNarrationManifestUseCase getManifestUseCase;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    @DisplayName("1. Anonymous GET to exact manifest endpoint /api/novel/chapters/{chapterId}/narration/manifest is permitted and returns 200 OK")
    void shouldAllowAnonymousAccessToNarrationManifest() throws Exception {
        UUID chapterId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        PublicNarrationVoiceDTO voice = new PublicNarrationVoiceDTO("kiemlai-male-01", "Minh Đức", true);
        PublicNarrationSegmentDTO segment = new PublicNarrationSegmentDTO(
                segmentId, 0, "READY", true, "/media/assets/" + UUID.randomUUID() + "/content"
        );
        PublicChapterNarrationManifestDTO manifest = new PublicChapterNarrationManifestDTO(
                chapterId, List.of(voice), voice, List.of(segment)
        );

        when(getManifestUseCase.execute(any(GetPublicChapterNarrationManifestQuery.class)))
                .thenReturn(manifest);

        mockMvc.perform(get("/api/novel/chapters/{chapterId}/narration/manifest", chapterId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.chapterId").value(chapterId.toString()))
                .andExpect(jsonPath("$.availableVoices[0].voiceKey").value("kiemlai-male-01"))
                .andExpect(jsonPath("$.availableVoices[0].displayName").value("Minh Đức"))
                .andExpect(jsonPath("$.availableVoices[0].defaultVoice").value(true))
                .andExpect(jsonPath("$.availableVoices[0].providerVoiceId").doesNotExist())
                .andExpect(jsonPath("$.selectedVoice.voiceKey").value("kiemlai-male-01"))
                .andExpect(jsonPath("$.selectedVoice.providerVoiceId").doesNotExist())
                .andExpect(jsonPath("$.segments[0].segmentId").value(segmentId.toString()))
                .andExpect(jsonPath("$.segments[0].segmentIndex").value(0))
                .andExpect(jsonPath("$.segments[0].healthStatus").value("READY"))
                .andExpect(jsonPath("$.segments[0].playable").value(true))
                .andExpect(jsonPath("$.segments[0].audioUrl").value(segment.audioUrl()))
                .andExpect(jsonPath("$.segments[0].failureDiagnostics").doesNotExist());
    }

    @Test
    @DisplayName("2. Anonymous GET to unrelated /api/novel/... endpoint is NOT public and redirects to login")
    void shouldDenyAnonymousAccessToUnrelatedNovelApiEndpoints() throws Exception {
        mockMvc.perform(get("/api/novel/unrelated-private-endpoint")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
